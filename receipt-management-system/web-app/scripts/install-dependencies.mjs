import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectDirectory = dirname(dirname(fileURLToPath(import.meta.url)));
const lockFile = join(projectDirectory, 'package-lock.json');
const dependencyDirectory = join(projectDirectory, 'node_modules');
const compilerEntry = join(dependencyDirectory, 'typescript', 'bin', 'tsc');
const markerFile = join(projectDirectory, '.maven-dependencies.sha256');
const lockHash = createHash('sha256').update(readFileSync(lockFile)).digest('hex');
const installedHash = existsSync(markerFile) ? readFileSync(markerFile, 'utf8').trim() : '';

if (installedHash !== lockHash || !existsSync(join(dependencyDirectory, '.package-lock.json'))
    || !existsSync(compilerEntry)) {
  const npmCli = process.env.npm_execpath;
  if (!npmCli) throw new Error('npm did not provide its CLI path to the Maven dependency installer.');

  const install = spawnSync(process.execPath, [npmCli, 'ci', '--include=optional', '--no-audit', '--no-fund'], {
    cwd: projectDirectory,
    stdio: 'inherit',
  });
  if (install.error) throw install.error;
  if (install.status !== 0) process.exit(install.status ?? 1);
  writeFileSync(markerFile, `${lockHash}\n`, 'utf8');
}
