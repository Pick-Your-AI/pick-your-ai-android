"""Run the real observer against explicitly synthetic offline Maven artifacts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', type=Path, required=True)
    args = parser.parse_args()
    observer = Path(__file__).resolve().parents[1] / 'talkback_dependency_observation.gradle'
    with tempfile.TemporaryDirectory(prefix='talkback-producer-control-') as temporary:
        root = Path(temporary)
        expected = {}
        for name in ('build-tool', 'runtime', 'unused'):
            directory = root / 'maven' / 'synthetic' / name / '1'
            directory.mkdir(parents=True)
            jar = directory / (name + '-1.jar')
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('fixture.txt', 'Synthetic observer regression: ' + name)
            (directory / (name + '-1.pom')).write_text(
                '<project><modelVersion>4.0.0</modelVersion><groupId>synthetic</groupId>'
                '<artifactId>' + name + '</artifactId><version>1</version></project>')
            expected['synthetic:' + name + ':1'] = hashlib.sha256(jar.read_bytes()).hexdigest()
        (root / 'settings.gradle').write_text("rootProject.name = 'synthetic-observer-control'\n")
        (root / 'build.gradle').write_text('''
buildscript {
    repositories { maven { url = uri('maven') } }
    dependencies { classpath 'synthetic:build-tool:1' }
}
// A test-only extension supplies the observer's Android tool attributes. No APK is built.
extensions.add('android', new Expando(compileSdkVersion: 'android-36', buildToolsVersion: '35.0.0'))
repositories { maven { url = uri('maven') } }
configurations { observed; untouched }
dependencies { observed 'synthetic:runtime:1'; untouched 'synthetic:unused:1' }
tasks.register('exerciseObserver') { doLast { configurations.observed.files.each { assert it.isFile() } } }
''')
        output = root / 'dependencies.json'
        subprocess.run([str(args.gradle.resolve()), '--project-dir', str(root),
                        '--init-script', str(observer),
                        '-Dtalkback.prerequisite.dependencies=' + str(output),
                        '--offline', '--no-daemon', '--no-configuration-cache', '--no-build-cache',
                        '--max-workers=1', 'exerciseObserver'], check=True, timeout=240,
                       env=dict(os.environ))
        receipt = json.loads(output.read_text())
        assert receipt['build_completed'] is True and receipt['preapproved_checksums'] is False
        assert set(receipt['components']) == {'synthetic:build-tool:1', 'synthetic:runtime:1'}, receipt['components']
        assert {row['component']: row['sha256'] for row in receipt['artifacts']} == {
            key: expected[key] for key in ('synthetic:build-tool:1', 'synthetic:runtime:1')}
        assert ':|1|classpath' in receipt['configurations']
        assert ':|0|observed' in receipt['configurations']
        assert ':|0|untouched' not in receipt['configurations']
        print('Real Gradle observer captured synthetic buildscript and runtime artifacts; unused configuration stayed unresolved')


if __name__ == '__main__':
    main()
