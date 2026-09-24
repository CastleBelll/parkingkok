/**
 * Jev — TypeSafe's System One model — for the small, fast judgements in this repo.
 *
 * ## What it is for, and what it is not
 * Jev answers typed questions about a piece of state with calibrated probabilities. It does
 * not write code, review a diff or design anything. The division of labour here is
 * deliberate: **Jev classifies, Claude reasons.** A decision that fits in a label and wants
 * an answer in milliseconds goes here; anything that needs to read the repo and think does
 * not.
 *
 * ## Why the questions live in this file
 * The API takes free-form `instructions`, which means the quality of an answer is a property
 * of the prompt, not of the call site. Keeping the rubrics here makes them reviewable, keeps
 * two callers from asking the same question two ways, and makes them testable without a
 * network — `TypeSafeClientConfig.fetch` is injectable, which is what `jev.test.ts` uses.
 *
 * Verified against the official docs on 2026-09-21: `POST /v1/systemone`,
 * `Authorization: Bearer`, question types `noul` / `choice` / `score`, default model
 * `jev-latest`, key from `TYPESAFE_API_KEY`. https://docs.typesafe.ai/api.md
 */

import { choice, noul, TypeSafeClient, type Fetch } from "@typesafe-ai/sdk";

/** The decisions this repo asks for, keyed by the name the CLI takes. */
export const DECISIONS = {
  /**
   * Is a shell command about to destroy something?
   *
   * A `noul` rather than a `choice` because the useful output is a probability that can be
   * thresholded, not a label: "0.97 destructive" and "0.51 destructive" deserve different
   * treatment and a label collapses them.
   */
  risk: {
    summary: "Is this shell command destructive or irreversible?",
    question: () =>
      noul(
        [
          "You are judging a shell command that is about to run in a developer's repository.",
          "Answer yes if running it could destroy work that is not recoverable from git or",
          "from a rebuild: deleting untracked files, force-pushing, dropping a database,",
          "rewriting history, uninstalling an app that holds user data, `rm -rf` outside a",
          "build directory.",
          "Answer no for reads, builds, tests, installs, formatters, and for deletions whose",
          "target is a build artefact, a cache or a derived-data directory.",
        ].join(" "),
      ),
  },

  /**
   * A failing test: is it the code, or is it the machine?
   *
   * This repo has a standing example — the Vision OCR suite fails on a cold start and passes
   * on the retry — and the cost of getting it wrong in either direction is real: chasing a
   * flake wastes an hour, and dismissing a real failure ships it.
   */
  failure: {
    summary: "Classify a failing build or test run",
    question: () =>
      choice("What kind of failure is this output?", {
        real_failure: "The code under test is wrong. The assertion describes a genuine defect.",
        flake:
          "Non-deterministic. Timing, ordering, a cold start, a shared device or a first-run model load. The same input would pass on a retry.",
        environment:
          "The machine, not the code: a missing SDK, no disk space, an unavailable device, a network failure, a revoked permission.",
        configuration:
          "A build or project setting is wrong or missing — a config file, a signing identity, a dependency version, a generated resource that is absent.",
      }),
  },

  /**
   * Which path a request should take, so that the cheap ones stay cheap.
   */
  route: {
    summary: "Classify a development request",
    question: () =>
      choice("What kind of work is this request asking for?", {
        lookup: "A fact that can be answered by reading one file or running one command.",
        quick_edit: "A small, local change to code that already exists.",
        feature: "New behaviour that needs design, several files, and tests.",
        debug: "Something is broken and the cause is not yet known.",
        review: "Judging existing code or a diff for correctness, risk or quality.",
        docs: "Writing or correcting documentation, comments or copy.",
      }),
  },

  /**
   * Does a change touch anything the contract documents govern?
   *
   * CLAUDE.md's rule is contract-first: `docs/` is fixed before the code is. The failure mode
   * this catches is the quiet one — a change that looks like an implementation detail and is
   * actually a contract change nobody wrote down.
   */
  contract: {
    summary: "Does this change need a docs/ update first?",
    question: () =>
      noul(
        [
          "This repository fixes its contracts in `docs/` before implementing them: the",
          "parking detection state machine, its thresholds and timeout windows, the",
          "cross-platform behavioural parity rules, the Firebase account model, and the",
          "privacy rules about what may leave the device.",
          "Answer yes if the described change alters any of those — a transition, a constant,",
          "what an event means, what is stored or sent, or what the two platforms must agree",
          "on. Answer no for refactoring, tests, tooling, copy and UI arrangement that leave",
          "the documented behaviour identical.",
        ].join(" "),
      ),
  },
} as const;

export type DecisionName = keyof typeof DECISIONS;

export const DECISION_NAMES = Object.keys(DECISIONS) as DecisionName[];

export function isDecisionName(value: string): value is DecisionName {
  return Object.hasOwn(DECISIONS, value);
}

/** What a caller gets back, flattened so a shell script can read it with one `jq`. */
export interface DecisionResult {
  decision: DecisionName;
  /** `choice` answers only: the selected label. */
  answer?: string;
  /** `noul` answers only: probability of yes, 0–1. */
  probability?: number;
  /** `choice` answers only, as reported by the model. */
  confidence?: number;
  /** `choice` answers only: the full distribution, which is the honest part. */
  probabilities?: Record<string, number>;
  usage: { input_tokens: number; output_tokens: number };
}

export interface RunOptions {
  /** Injected by tests; production passes nothing and the SDK uses global `fetch`. */
  fetch?: Fetch;
  /** Overrides `TYPESAFE_API_KEY`, which is where it normally comes from. */
  apiKey?: string;
}

/**
 * Asks one question about one piece of state.
 *
 * Deliberately one round trip and no caching: these questions are asked about text that is
 * different every time, and a cache keyed on it would never hit.
 */
export async function runDecision(
  decision: DecisionName,
  state: string,
  options: RunOptions = {},
): Promise<DecisionResult> {
  const client = new TypeSafeClient({
    ...(options.apiKey === undefined ? {} : { apiKey: options.apiKey }),
    ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
  });

  const { answers, usage } = await client.systemOne({
    state,
    questions: { verdict: DECISIONS[decision].question() },
  });

  const verdict = answers.verdict;
  const result: DecisionResult = { decision, usage };
  if (verdict.type === "noul") {
    result.probability = verdict.noul;
  } else if (verdict.type === "choice") {
    result.answer = verdict.choice;
    result.confidence = verdict.confidence;
    result.probabilities = { ...verdict.probabilities };
  }
  return result;
}
