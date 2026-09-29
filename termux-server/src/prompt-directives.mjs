import fs from 'node:fs/promises';

export const PROMPT_DIRECTIVES_SCHEMA = 'selfrun-prompt-directives-v1';
export const DEFAULT_TURN_START_DIRECTIVE =
  '입력 확인만 하고 응답을 종료하지 마십시오. 지금 즉시 현재 턴에 지정된 SelfRun 문서와 상태를 읽고, 현재 phase에 할당된 실질 작업을 수행하십시오. 현재 턴의 필수 작업과 Result 최종 확정이 완료되기 전에는 단순 확인 응답으로 턴을 종료하지 마십시오.';
export const DEFAULT_TURN_CONTINUE_DIRECTIVE =
  '현재 턴에 할당된 잔여작업이 있으면 계속 수행해';

function clean(value) {
  return typeof value === 'string' ? value.trim() : '';
}

export function appendTurnStartDirective(prompt, directive) {
  const base = String(prompt ?? '').replace(/\s+$/u, '');
  const text = clean(directive);
  if (!text) return base;
  return [base, '', '[SelfRun 서버 실행 지시]', text].join('\n');
}

export class PromptDirectiveStore {
  constructor(config = {}) {
    this.file = clean(config.promptDirectivesFile);
    this.defaults = Object.freeze({
      turnStartDirective: clean(config.turnStartDirective) || DEFAULT_TURN_START_DIRECTIVE,
      turnContinueDirective: clean(config.recoveryPrompt) || DEFAULT_TURN_CONTINUE_DIRECTIVE,
    });
    this.lastValid = this.defaults;
    this.source = 'DEFAULT';
    this.error = null;
  }

  snapshot() {
    return {
      ...this.lastValid,
      source: this.source,
      error: this.error,
    };
  }

  async current() {
    if (!this.file) {
      this.lastValid = this.defaults;
      this.source = 'DEFAULT';
      this.error = null;
      return this.snapshot();
    }

    try {
      const raw = await fs.readFile(this.file, 'utf8');
      const parsed = JSON.parse(raw);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('prompt directives config must be an object');
      }
      const schema = clean(parsed.schema);
      if (schema && schema !== PROMPT_DIRECTIVES_SCHEMA) {
        throw new Error('unsupported prompt directives schema: ' + schema);
      }
      this.lastValid = Object.freeze({
        turnStartDirective: clean(parsed.turn_start_directive)
          || this.defaults.turnStartDirective,
        turnContinueDirective: clean(parsed.turn_continue_directive)
          || this.defaults.turnContinueDirective,
      });
      this.source = 'FILE';
      this.error = null;
      return this.snapshot();
    } catch (error) {
      if (error?.code === 'ENOENT') {
        this.lastValid = this.defaults;
        this.source = 'DEFAULT';
        this.error = null;
        return this.snapshot();
      }
      this.source = 'LAST_VALID';
      this.error = String(error?.message || error).slice(0, 500);
      return this.snapshot();
    }
  }
}
