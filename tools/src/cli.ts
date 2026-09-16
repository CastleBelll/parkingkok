#!/usr/bin/env node
/**
 * `trace2fixture` — the command line around `convert.ts` and `fixture.ts`.
 *
 *   convert  <trace.json> [--out <file>] [--name <name>] [--initial-state <STATE>] [--force]
 *   validate [paths...] [--allow-draft]
 *
 * Drafts land in `platform-tests/drafts/` rather than next to the real fixtures, so a
 * pending review never breaks the fixture gate. Promoting a draft is: fill in `expected`,
 * delete `_todo`, move the file up one directory.
 */

import { readdirSync, readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { parseArgs } from 'node:util';

import { DETECTION_STATES, type DetectionState } from './contract';
import { convertTraceToJson } from './convert';
import { parseFixtureText } from './fixture';
import { ValidationError } from './schema';
import { parseTraceText } from './trace';

const FIXTURE_DIR = 'platform-tests';
const DRAFT_DIR = join(FIXTURE_DIR, 'drafts');
const STDOUT = '-';

const USAGE = `trace2fixture — trace (contract §9) to parity fixture (contract §8)

  convert <trace.json> [options]
    --out <file>            Output path, or "-" for stdout.
                            Default: ${DRAFT_DIR}/<name>.json
    --name <name>           Fixture name. Default: the trace's sessionId.
    --initial-state <STATE> Engine state at session start. Default: IDLE.
                            One of: ${DETECTION_STATES.join(', ')}
    --force                 Overwrite an existing output file.

  validate [paths...] [--allow-draft]
    Paths may be files or directories. Default: ${FIXTURE_DIR}
    Without --allow-draft, a converted draft is a failure: a recording is not an answer key.
`;

function fixtureFilesIn(target: string): string[] {
  if (!existsSync(target)) throw new ValidationError(`${target}: no such file or directory`);
  const entries = readdirSync(target, { withFileTypes: true });
  if (entries.length === 0) return [];
  return entries
    .filter((entry) => entry.isFile() && entry.name.endsWith('.json'))
    .map((entry) => join(target, entry.name))
    .sort();
}

function resolveTargets(paths: readonly string[]): string[] {
  return paths.flatMap((path) => {
    if (!existsSync(path)) throw new ValidationError(`${path}: no such file or directory`);
    return path.endsWith('.json') ? [path] : fixtureFilesIn(path);
  });
}

function toDetectionState(value: string | undefined): DetectionState | undefined {
  if (value === undefined) return undefined;
  if (!(DETECTION_STATES as readonly string[]).includes(value)) {
    throw new ValidationError(
      `--initial-state "${value}" is not a contract §3 state; expected one of ` +
        DETECTION_STATES.join(', '),
    );
  }
  return value as DetectionState;
}

function runConvert(argv: readonly string[]): number {
  const { values, positionals } = parseArgs({
    args: [...argv],
    options: {
      out: { type: 'string' },
      name: { type: 'string' },
      'initial-state': { type: 'string' },
      force: { type: 'boolean', default: false },
    },
    allowPositionals: true,
  });

  const input = positionals[0];
  if (input === undefined || positionals.length > 1) {
    throw new ValidationError('convert takes exactly one trace file');
  }

  const trace = parseTraceText(readFileSync(input, 'utf8'), basename(input));
  const name = values.name ?? trace.sessionId;
  const json = convertTraceToJson(trace, {
    name,
    initialState: toDetectionState(values['initial-state']),
  });

  const out = values.out ?? join(DRAFT_DIR, `${name}.json`);
  if (out === STDOUT) {
    process.stdout.write(json);
    return 0;
  }
  if (existsSync(out) && !values.force) {
    throw new ValidationError(`${out} already exists; pass --force to overwrite it`);
  }

  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, json, 'utf8');
  console.log(`wrote ${out} (draft — fill in "expected", then delete "_todo")`);
  return 0;
}

function runValidate(argv: readonly string[]): number {
  const { values, positionals } = parseArgs({
    args: [...argv],
    options: { 'allow-draft': { type: 'boolean', default: false } },
    allowPositionals: true,
  });

  const targets = resolveTargets(positionals.length > 0 ? positionals : [FIXTURE_DIR]);
  if (targets.length === 0) {
    console.error('no fixture files found');
    return 1;
  }

  let failed = 0;
  for (const target of targets) {
    try {
      // The FAIL line already names the file; the path inside a message is the JSON path.
      const document = parseFixtureText(readFileSync(target, 'utf8'), 'fixture', {
        allowDraft: values['allow-draft'],
      });
      console.log(`ok    ${target} (${document.kind}, ${String(document.events.length)} events)`);
    } catch (error) {
      failed += 1;
      console.error(`FAIL  ${target}`);
      console.error(`      ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  console.log(
    `${String(targets.length - failed)}/${String(targets.length)} fixture(s) valid`,
  );
  return failed === 0 ? 0 : 1;
}

function main(argv: readonly string[]): number {
  const [command, ...rest] = argv;
  switch (command) {
    case 'convert':
      return runConvert(rest);
    case 'validate':
      return runValidate(rest);
    case undefined:
    case '--help':
    case '-h':
    case 'help':
      console.log(USAGE);
      return command === undefined ? 1 : 0;
    default:
      console.error(`unknown command "${command}"\n`);
      console.error(USAGE);
      return 1;
  }
}

try {
  process.exitCode = main(process.argv.slice(2));
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}
