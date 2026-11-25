// Labs › Keyboard › Neural autocorrect: a small causal language model (llama.cpp) scoring the words autocorrect is
// choosing between, with the text before them as context. The same file builds the Android library (JNI for
// ly.anoma.hex.typing.NeuralWords) and, on a desktop, the library the JVM typing benchmark loads.
//
// A candidate's score is how unlikely the model finds it, in nats: -log P(" candidate" | context) - log P(the word
// ends there | context, candidate). The second term keeps a prefix ("tail" of "tailscale") from looking likelier than
// the whole word. The context's state stays in the KV cache between calls, so a word typed after the last one only
// decodes the new tokens; the candidates are decoded together in one batch, each in its own sequence copied from it.

#include "llama.h"

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr int kMaxCandidates = 12;  // the keyboard sends the spelling and 9 readings (SpatialDecoder.NEURAL_READINGS)
constexpr int kMaxWordTokens = 12;   // a candidate of more tokens is not scored, and costs kUnscored
constexpr float kUnscored = 99.0f;
constexpr int kContextTokens = 256;  // the most context kept, newest tokens (NeuralWords.context keeps it under)

struct Scorer {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_vocab = 0;
    llama_token start = -1;              // what every context begins with: BOS, else the end-of-text token
    std::vector<char> boundary;          // tokens that begin a new word or end one: a space, a mark, end of text
    std::vector<llama_token> cached;     // the context in sequence 0
    std::vector<float> last;             // its last token's logits
    std::mutex lock;
};

std::vector<llama_token> tokenize(const Scorer & s, const std::string & text) {
    std::vector<llama_token> out(text.size() + 8);
    int n = llama_tokenize(s.vocab, text.c_str(), (int32_t) text.size(), out.data(), (int32_t) out.size(), false, false);
    if (n < 0) { out.resize(-n); n = llama_tokenize(s.vocab, text.c_str(), (int32_t) text.size(), out.data(), (int32_t) out.size(), false, false); }
    out.resize(std::max(n, 0));
    return out;
}

std::string piece(const Scorer & s, llama_token t) {
    char buf[64];
    int n = llama_token_to_piece(s.vocab, t, buf, sizeof buf, 0, false);
    return n > 0 ? std::string(buf, n) : std::string();
}

bool startsBoundary(const std::string & p) {
    if (p.empty()) return false;
    unsigned char c = (unsigned char) p[0];
    // A space or newline starts the next word; these marks end this one. An apostrophe or hyphen continues it.
    return c == ' ' || c == '\n' || c == '\t' || std::strchr(".,!?;:\")]}…", c) != nullptr;
}

// One row of logits' normaliser, log Σ exp, and with [boundary] also -log P(any boundary token), in one pass over the
// vocabulary in single precision (it runs once for every token a candidate has; a row is ~50k entries).
struct RowStats { double lse; double boundary; };
RowStats rowStats(const float * row, int n, const char * boundary) {
    float max = row[0];
    for (int i = 1; i < n; i++) max = std::max(max, row[i]);
    float sum = 0, ends = 0;
    for (int i = 0; i < n; i++) {
        float e = std::exp(row[i] - max);
        sum += e;
        if (boundary) ends += boundary[i] ? e : 0.0f;
    }
    double lse = max + std::log((double) sum);
    return {lse, boundary ? (ends > 0 ? std::log((double) sum) - std::log((double) ends) : 50.0) : NAN};
}
double logSumExp(const float * row, int n) { return rowStats(row, n, nullptr).lse; }

// -log P(token) from one row of logits.
double tokenCost(const float * row, int n, llama_token t, double lse) { return lse - row[t]; }

// Puts [tokens] in sequence 0, decoding only what the cache doesn't hold, and leaves its last logits in s.last.
bool prime(Scorer & s, const std::vector<llama_token> & tokens) {
    size_t same = 0;
    while (same < tokens.size() && same < s.cached.size() && tokens[same] == s.cached[same]) same++;
    if (same == tokens.size() && same == s.cached.size() && !s.last.empty()) return true;
    // At least the last token is decoded again, for its logits.
    if (same == tokens.size()) same--;
    llama_memory_seq_rm(llama_get_memory(s.ctx), 0, (llama_pos) same, -1);
    s.cached.resize(same);
    llama_batch batch = llama_batch_init((int32_t) (tokens.size() - same), 0, 1);
    for (size_t i = same; i < tokens.size(); i++) {
        int k = batch.n_tokens++;
        batch.token[k] = tokens[i]; batch.pos[k] = (llama_pos) i;
        batch.n_seq_id[k] = 1; batch.seq_id[k][0] = 0; batch.logits[k] = i + 1 == tokens.size();
    }
    bool ok = llama_decode(s.ctx, batch) == 0;
    if (ok) {
        const float * row = llama_get_logits_ith(s.ctx, batch.n_tokens - 1);
        s.last.assign(row, row + s.n_vocab);
        s.cached = tokens;
    } else {
        llama_memory_seq_rm(llama_get_memory(s.ctx), 0, -1, -1);
        s.cached.clear(); s.last.clear();
    }
    llama_batch_free(batch);
    return ok;
}

std::vector<llama_token> contextTokens(const Scorer & s, const std::string & context) {
    std::vector<llama_token> tokens = tokenize(s, context);
    if ((int) tokens.size() > kContextTokens) tokens.erase(tokens.begin(), tokens.end() - kContextTokens);
    tokens.insert(tokens.begin(), s.start);
    return tokens;
}

// Reads [context] into the cache ahead of the word typed after it, so scoring that word decodes only its candidates.
bool prepare(Scorer & s, const std::string & context) {
    std::lock_guard<std::mutex> guard(s.lock);
    return prime(s, contextTokens(s, context));
}

bool score(Scorer & s, const std::string & context, const std::vector<std::string> & candidates, std::vector<float> & out) {
    std::lock_guard<std::mutex> guard(s.lock);
    out.assign(candidates.size(), NAN);
    if (candidates.empty()) return true;
    std::vector<llama_token> ctxTokens = contextTokens(s, context);
    if (!prime(s, ctxTokens)) return false;
    const double lseContext = logSumExp(s.last.data(), s.n_vocab);

    size_t n = std::min(candidates.size(), (size_t) kMaxCandidates);
    std::vector<std::vector<llama_token>> toks(n);
    for (size_t i = 0; i < n; i++) {
        // A word after text has its space; the first word of a field has none.
        toks[i] = tokenize(s, (context.empty() ? "" : " ") + candidates[i]);
        if (toks[i].size() > kMaxWordTokens) toks[i].clear();
    }
    // The candidates as a tree of tokens: readings that begin alike ("tail scale", "tail sale") share their first
    // tokens, decoded once in all their sequences. node[i][j] is candidate i's j-th token's node, its batch position.
    struct Node { llama_token token; int depth; std::vector<llama_seq_id> seqs; };
    std::vector<Node> nodes;
    std::map<std::pair<int, llama_token>, int> children;  // (parent node, token) -> node; parent -1 is the context
    std::vector<std::vector<int>> path(n);
    for (size_t i = 0; i < n; i++) {
        int parent = -1;
        for (size_t j = 0; j < toks[i].size(); j++) {
            auto key = std::make_pair(parent, toks[i][j]);
            auto found = children.find(key);
            int node;
            if (found != children.end()) node = found->second;
            else { node = (int) nodes.size(); nodes.push_back({toks[i][j], (int) j, {}}); children.emplace(key, node); }
            nodes[node].seqs.push_back((llama_seq_id) (i + 1));
            path[i].push_back(node);
            parent = node;
        }
    }
    llama_memory_t mem = llama_get_memory(s.ctx);
    for (size_t i = 0; i < n; i++) if (!toks[i].empty()) llama_memory_seq_cp(mem, 0, (llama_seq_id) (i + 1), -1, -1);
    llama_batch batch = llama_batch_init(std::max((int) nodes.size(), 1), 0, kMaxCandidates);
    const llama_pos base = (llama_pos) ctxTokens.size();
    for (const Node & node : nodes) {
        int k = batch.n_tokens++;
        batch.token[k] = node.token; batch.pos[k] = base + (llama_pos) node.depth;
        batch.n_seq_id[k] = (int32_t) node.seqs.size();
        for (size_t q = 0; q < node.seqs.size(); q++) batch.seq_id[k][q] = node.seqs[q];
        batch.logits[k] = true;
    }
    bool ok = batch.n_tokens == 0 || llama_decode(s.ctx, batch) == 0;
    if (ok) {
        // A node where a candidate ends needs the boundary's probability too; each row is read once.
        std::vector<char> ends(nodes.size(), 0);
        for (size_t i = 0; i < n; i++) if (!path[i].empty()) ends[path[i].back()] = 1;
        std::vector<RowStats> stats(nodes.size(), {NAN, NAN});
        auto row = [&](int node) {
            const float * r = llama_get_logits_ith(s.ctx, node);
            if (std::isnan(stats[node].lse)) stats[node] = rowStats(r, s.n_vocab, ends[node] ? s.boundary.data() : nullptr);
            return r;
        };
        for (size_t i = 0; i < n; i++) {
            // A candidate too long to score is one the model finds most unlikely.
            if (toks[i].empty()) { out[i] = kUnscored; continue; }
            double cost = tokenCost(s.last.data(), s.n_vocab, toks[i][0], lseContext);
            for (size_t j = 0; j < toks[i].size(); j++) {
                int node = path[i][j];
                const float * r = row(node);
                cost += j + 1 < toks[i].size() ? tokenCost(r, s.n_vocab, toks[i][j + 1], stats[node].lse) : stats[node].boundary;
            }
            out[i] = (float) cost;
        }
    }
    for (size_t i = 0; i < n; i++) llama_memory_seq_rm(mem, (llama_seq_id) (i + 1), -1, -1);
    llama_batch_free(batch);
    return ok;
}

void quiet(ggml_log_level, const char *, void *) {}

Scorer * load(const char * path, int threads) {
    static std::once_flag once;
    std::call_once(once, [] { llama_log_set(quiet, nullptr); llama_backend_init(); });
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    // Read, not mapped: the CPU backend repacks the 8-bit weights for its dot-product kernels (twice as fast on a
    // Flip6), and a mapped file would stay resident beside that copy. 224 MB in all against 334 MB mapped.
    mp.load_mode = LLAMA_LOAD_MODE_NONE;
    llama_model * model = llama_model_load_from_file(path, mp);
    if (!model) return nullptr;
    llama_context_params cp = llama_context_default_params();
    // The context (up to kContextTokens and its start) and every candidate's tokens fit at once; only the candidates'
    // tokens need scores, so the scores' buffer holds that many rows of the vocabulary, not a whole batch's.
    cp.n_ctx = 640; cp.n_batch = 512; cp.n_ubatch = 128;
    cp.n_outputs_max = kMaxCandidates * kMaxWordTokens;
    cp.n_seq_max = kMaxCandidates + 1; cp.kv_unified = true;
    cp.n_threads = threads; cp.n_threads_batch = threads;
    cp.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return nullptr; }
    auto * s = new Scorer;
    s->model = model; s->ctx = ctx; s->vocab = llama_model_get_vocab(model);
    s->n_vocab = llama_vocab_n_tokens(s->vocab);
    s->start = llama_vocab_get_add_bos(s->vocab) ? llama_vocab_bos(s->vocab) : llama_vocab_eos(s->vocab);
    s->boundary.resize(s->n_vocab);
    for (int t = 0; t < s->n_vocab; t++)
        s->boundary[t] = llama_vocab_is_eog(s->vocab, t) || startsBoundary(piece(*s, t));
    return s;
}

void unload(Scorer * s) {
    if (!s) return;
    llama_free(s->ctx); llama_model_free(s->model); delete s;
}

std::string utf8(JNIEnv * env, jstring text) {
    const char * chars = env->GetStringUTFChars(text, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(text, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_ly_anoma_hex_typing_NeuralWords_nativeLoad(JNIEnv * env, jclass, jstring path, jint threads) {
    return (jlong) load(utf8(env, path).c_str(), threads);
}

JNIEXPORT jfloatArray JNICALL Java_ly_anoma_hex_typing_NeuralWords_nativeScore(JNIEnv * env, jclass, jlong handle,
                                                                               jstring context, jobjectArray candidates) {
    auto * s = (Scorer *) handle;
    if (!s) return nullptr;
    std::vector<std::string> words;
    jsize n = env->GetArrayLength(candidates);
    for (jsize i = 0; i < n; i++) {
        auto word = (jstring) env->GetObjectArrayElement(candidates, i);
        words.push_back(utf8(env, word));
        env->DeleteLocalRef(word);
    }
    std::vector<float> out;
    if (!score(*s, utf8(env, context), words, out)) return nullptr;
    jfloatArray result = env->NewFloatArray((jsize) out.size());
    env->SetFloatArrayRegion(result, 0, (jsize) out.size(), out.data());
    return result;
}

JNIEXPORT jboolean JNICALL Java_ly_anoma_hex_typing_NeuralWords_nativePrepare(JNIEnv * env, jclass, jlong handle, jstring context) {
    auto * s = (Scorer *) handle;
    return s && prepare(*s, utf8(env, context)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_ly_anoma_hex_typing_NeuralWords_nativeFree(JNIEnv *, jclass, jlong handle) {
    unload((Scorer *) handle);
}

}  // extern "C"

#ifdef HEX_TYPING_CLI
#include <chrono>
#include <cstdio>
#include <cstdlib>
// hextyping-cli MODEL "context" word...: each word's cost, and the time the call took.
// hextyping-cli MODEL --bench THREADS...: how long space waits on NeuralAutocorrect's speed-test words, at each count.
static const char * kBench[][7] = {
    {"I want to", "buy", "by", "bye", "bu", "buyt", "vuy"},
    {"Can you send me the", "file", "fike", "fire", "fine", "fill", "flie"},
    {"I'll be there in ten", "minutes", "minutess", "mintues", "minute", "mines", "minuets"},
    {"Thanks for the", "help", "hepl", "hell", "held", "helo", "hello"},
    {"We should meet at the coffee", "shop", "shoo", "ship", "chop", "shops", "show"},
    {"My home server is on", "tailscale", "tail scale", "tails ale", "Tailscale", "tailsale", "tsilscale"},
    {"Did you see the", "game", "gane", "gamer", "came", "fame", "games"},
    {"I'm running a little", "late", "lare", "kate", "lame", "lake", "later"},
    {"Could you pick up some", "milk", "mikl", "mill", "mild", "mile", "silk"},
    {"It was really nice to", "meet", "meat", "mert", "melt", "met", "mees"},
    {"The meeting got moved to", "tomorrow", "tomorow", "tomorrows", "tomato", "morrow", "tomb"},
    {"Let me know when you get", "home", "hime", "hone", "hope", "come", "homes"},
    {"I think we need to", "talk", "tslk", "tall", "take", "task", "walk"},
    {"Happy birthday! Hope you have a great", "day", "dsy", "das", "dad", "say", "fay"},
    {"Sorry, I didn't", "see", "sew", "sea", "dee", "seem", "sees"},
    {"Where did you put the", "keys", "jeys", "key", "kets", "lets", "keyd"},
    {"I'm going to the", "gym", "gum", "hym", "gin", "fym", "gyms"},
    {"Can we reschedule for next", "week", "weel", "weak", "weeks", "wee", "seek"},
    {"That sounds like a good", "idea", "ides", "idra", "ideal", "ideas", "odea"},
    {"Just pushed the fix to", "main", "maon", "mail", "rain", "man", "mains"},
};

// Words space reads many ways (an unknown word, every split of it): the most a word sends, 16 readings.
static const char * kWide[][17] = {
    {"My home server runs on", "tailscale", "tail scale", "tails scale", "tails ale", "tail sale", "tail scales", "tails sale",
     "tail's cale", "tai lscale", "tailsc ale", "tails cale", "tal scale", "tail scald", "tile scale", "tall scale", "tails scaled"},
    {"We deployed it with", "kubernetes", "kuber netes", "kube rnetes", "kub ernetes", "kubern etes", "kuberne tes", "ku bernetes",
     "kuber nets", "kubert nets", "cuber nets", "kibernetes", "kubernets", "kuber nets s", "kube nets", "kubrnetes", "kuberneyes"},
    {"Thanks so much for the", "thankyouverymuch", "thank you very much", "thank you verymuch", "thankyou very much",
     "thank youvery much", "thank you very mutch", "thanks you very much", "thank you vary much", "thank your very much",
     "thank you every much", "thank you very much h", "thank yo very much", "thank you ver much", "thank you very mich",
     "thank tou very much", "thank you very nuch"},
};

static int bench(const char * path, int argc, char ** argv) {
    using clock = std::chrono::steady_clock;
    for (int a = 0; a < argc; a++) {
        int threads = std::atoi(argv[a]);
        auto t0 = clock::now();
        Scorer * s = load(path, threads);
        if (!s) { std::fprintf(stderr, "load failed\n"); return 1; }
        double loadMs = std::chrono::duration<double, std::milli>(clock::now() - t0).count();
        std::vector<double> scored, prepared;
        for (int round = 0; round < 3; round++) {
            for (auto & row : kBench) {
                std::vector<std::string> words(row + 1, row + 7);
                std::vector<float> out;
                auto p0 = clock::now();
                prepare(*s, row[0]);
                auto p1 = clock::now();
                score(*s, row[0], words, out);
                auto p2 = clock::now();
                if (round > 0) {  // the first round warms the caches
                    prepared.push_back(std::chrono::duration<double, std::milli>(p1 - p0).count());
                    scored.push_back(std::chrono::duration<double, std::milli>(p2 - p1).count());
                }
            }
        }
        std::vector<double> wide, ten;
        for (int round = 0; round < 4; round++) {
            for (auto & row : kWide) for (int count : {16, 10}) {
                std::vector<std::string> words(row + 1, row + 1 + count);
                std::vector<float> out;
                prepare(*s, row[0]);
                auto w0 = clock::now();
                score(*s, row[0], words, out);
                if (round > 0) (count == 16 ? wide : ten).push_back(std::chrono::duration<double, std::milli>(clock::now() - w0).count());
            }
        }
        std::sort(wide.begin(), wide.end()); std::sort(ten.begin(), ten.end());
        std::sort(scored.begin(), scored.end()); std::sort(prepared.begin(), prepared.end());
        auto at = [](std::vector<double> & v, double q) { return v[(size_t) ((v.size() - 1) * q)]; };
        std::printf("threads %d: load %.0f ms · space median %.1f ms, p95 %.1f ms, max %.1f ms · reading the text before median %.1f ms\n",
                    threads, loadMs, at(scored, .5), at(scored, .95), scored.back(), at(prepared, .5));
        std::printf("           16 readings: median %.1f ms, max %.1f ms · 10 readings: median %.1f ms, max %.1f ms\n",
                    at(wide, .5), wide.back(), at(ten, .5), ten.back());
        unload(s);
    }
    return 0;
}

int main(int argc, char ** argv) {
    if (argc >= 3 && std::string(argv[2]) == "--bench") return bench(argv[1], argc - 3, argv + 3);
    if (argc < 4) { std::fprintf(stderr, "usage: %s MODEL CONTEXT WORD...\n", argv[0]); return 2; }
    Scorer * s = load(argv[1], 4);
    if (!s) { std::fprintf(stderr, "load failed\n"); return 1; }
    std::vector<std::string> words(argv + 3, argv + argc);
    std::vector<float> out;
    prepare(*s, argv[2]);
    for (int round = 0; round < 2; round++) {
        auto t0 = std::chrono::steady_clock::now();
        score(*s, argv[2], words, out);
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        std::printf("round %d: %.1f ms\n", round, ms);
    }
    for (size_t i = 0; i < words.size(); i++) std::printf("%8.2f  %s\n", out[i], words[i].c_str());
    unload(s);
}
#endif
