#!/usr/bin/env python3
"""Score swipe typing on synthetic paths: how often the first word is right, how often the word is typed or in
the strip (top 4), and the time per swipe. The paths are swipe_paths.py's, the same ones the iPhone replay reads,
from the held-out sentences; each is decoded by the keyboard's own SwipeReader (SwipeReplay.java) on the host
JVM, or with --device on an emulator or phone against the installed APK, as replay.sh runs the tap replay.

  android/benchmark/typing/swipe-bench.py [LANG ...] [--noise typical] [--device SERIAL] [--out DIR]
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ANDROID = HERE.parents[1]
MODELS = ANDROID / 'app/src/main/assets/typing'
LANGUAGES = ['en', 'es', 'de', 'nb', 'fr', 'it', 'pt']


def paths(lang, noise, out):
    """The path set, made once per language and noise."""
    target = out / f'{lang}-{noise}.tsv.gz'
    if not target.is_file():
        subprocess.run(['python3', HERE / 'swipe_paths.py', lang, target, '--noise', noise], check=True)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('languages', nargs='*', help=' '.join(LANGUAGES) + ' (default: all)')
    parser.add_argument('--noise', default='typical', choices=['typical', 'neat', 'sloppy', 'vague', 'none'])
    parser.add_argument('--device', help='adb serial: run on the device against the installed APK')
    parser.add_argument('--out', type=Path, default=ANDROID / 'build/swipe-bench')
    parser.add_argument('--neural', type=Path, help='Labs › Keyboard › Neural autocorrect: the GGUF model (host only)')
    parser.add_argument('--neural-lib', type=Path, help="the host's libhextyping.so")
    parser.add_argument('--neural-weight', type=float)
    parser.add_argument('--neural-readings', type=int, help="how many of the path's readings the model chooses among")
    parser.add_argument('--neural-path', type=float, help="the path cost's weight beside the model's")
    parser.add_argument('--neural-loose', type=float, help='the path cost a letter above which a swipe counts as loose')
    parser.add_argument('--neural-dump', action='store_true', help="write {lang}-{noise}-neural-dump.tsv: each swipe's 12 readings, path and model costs")
    args = parser.parse_args()
    if not set(args.languages) <= set(LANGUAGES):
        parser.error('languages: ' + ' '.join(LANGUAGES))
    args.languages = args.languages or LANGUAGES
    args.out.mkdir(parents=True, exist_ok=True)
    jdk = Path(os.environ['JAVA_HOME']) / 'bin' if 'JAVA_HOME' in os.environ else Path('')
    with tempfile.TemporaryDirectory() as scratch:
        tmp = Path(scratch)
        cp = tmp / 'classpath'
        subprocess.run([ANDROID / 'gradlew', '-q', '-I', HERE / 'gate-classpath.gradle', ':app:typingGateClasspath',
                        '-x', 'prepareSpeechModel', '-PgateClasspath=' + str(cp)], cwd=ANDROID, check=True)
        classes = tmp / 'classes'
        subprocess.run([jdk / 'javac', '-encoding', 'UTF-8', '--release', '8', '-Xlint:-options', '-cp', cp.read_text(), '-d', classes,
                        HERE / 'SwipeReplay.java'], check=True)
        if args.device:
            adb = ['adb', '-s', args.device]
            sdk = Path(os.environ['ANDROID_HOME'])
            lib = sdk / 'platforms/android-36/android.jar'
            tools = max((sdk / 'build-tools').iterdir())
            subprocess.run([jdk / 'jar', 'cf', tmp / 'replay.jar', '-C', classes, '.'], check=True)
            subprocess.run([tools / 'd8', '--min-api', '31', '--lib', lib, '--output', tmp / 'replay-dex.jar', tmp / 'replay.jar'], check=True)
            dev = '/data/local/tmp/hex-swipe'
            subprocess.run(adb + ['shell', 'mkdir', '-p', dev], check=True)
            subprocess.run(adb + ['push', tmp / 'replay-dex.jar', dev + '/replay.jar'], check=True, capture_output=True)
            subprocess.run(adb + ['push', MODELS / 'never-suggest.tsv', dev], check=True, capture_output=True)
            apk = subprocess.check_output(adb + ['shell', 'pm', 'path', 'ly.anoma.hex'], text=True).strip().removeprefix('package:')
        for lang in args.languages:
            source = paths(lang, args.noise, args.out)
            words = args.out / f'{lang}-{args.noise}{"-neural" if args.neural else ""}-words.tsv'
            # Held-out words an earlier model lacked, swiped and scored apart (SwipeReplay --outside).
            listed = HERE / f'swipe-outside-{lang}.txt'
            outside = ['--outside', listed] if listed.is_file() else []
            if args.device:
                for side in ['hextm', 'capitals', 'valid']:
                    subprocess.run(adb + ['shell', 'rm', '-f', f'{dev}/{lang}.{side}'], check=True)
                    if (MODELS / f'{lang}.{side}').is_file():
                        subprocess.run(adb + ['push', MODELS / f'{lang}.{side}', dev], check=True, capture_output=True)
                subprocess.run(adb + ['push', source, f'{dev}/{source.name}'], check=True, capture_output=True)
                if outside:
                    subprocess.run(adb + ['push', listed, f'{dev}/{listed.name}'], check=True, capture_output=True)
                report = subprocess.check_output(adb + ['shell', f'CLASSPATH={dev}/replay.jar:{apk} app_process /system/bin '
                                                        f'local.hex.bench.SwipeReplay {dev}/{lang}.hextm {lang} {dev}/{source.name} {dev}/words.tsv'
                                                        + (f' --outside {dev}/{listed.name}' if outside else '')], text=True)
                subprocess.run(adb + ['shell', 'rm', f'{dev}/{source.name}'], check=True)
                subprocess.run(adb + ['pull', dev + '/words.tsv', words], check=True, capture_output=True)
            else:
                neural = []
                if args.neural:
                    neural = [f'-Dhex.typing.model={args.neural}', f'-Dhex.typing.lib={args.neural_lib}',
                              f'-Dhex.typing.sentences={HERE / "held-out" / f"{lang}.tsv.gz"}']
                    if args.neural_weight is not None:
                        neural.append(f'-Dhex.typing.weight={args.neural_weight}')
                    if args.neural_readings is not None:
                        neural.append(f'-Dhex.swipe.readings={args.neural_readings}')
                    if args.neural_path is not None:
                        neural.append(f'-Dhex.swipe.path={args.neural_path}')
                    if args.neural_loose is not None:
                        neural.append(f'-Dhex.swipe.loose={args.neural_loose}')
                    if args.neural_dump:
                        neural.append(f'-Dhex.swipe.dump={args.out / f"{lang}-{args.noise}-neural-dump.tsv"}')
                report = subprocess.check_output([jdk / 'java', '-Xmx1g', '-Dfile.encoding=UTF-8', *neural, '-cp', str(classes) + os.pathsep + cp.read_text(),
                                                  'local.hex.bench.SwipeReplay', MODELS / f'{lang}.hextm', lang, source, words] + outside, text=True)
            (args.out / f'{lang}-{args.noise}{"-device" if args.device else ""}{"-neural" if args.neural else ""}.txt').write_text(report)
            print(report, flush=True)


main()
