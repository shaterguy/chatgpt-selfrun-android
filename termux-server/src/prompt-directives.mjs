import fs from 'node:fs/promises';

export const PROMPT_DIRECTIVES_SCHEMA = 'selfrun-prompt-directives-v1';
export const DEFAULT_TURN_START_DIRECTIVE =
  '필요한 도구가 바로 보이지 않더라도 현재 제공된 도구를 확인해서 계속 진행해. 도구가 없다고 단정한 채 끝내지 말고, 이번 턴의 실제 작업과 결과 문서 확정까지 마친 뒤 답변해.';
export const DEFAULT_TURN_CONTINUE_DIRECTIVE =
  '현재 턴에 할당된 잔여작업이 있으면 계속 수행해';

function clean(value) {
  return typeof value === 'string' ? value.trim() : '';
}

export function appendTurnStartDirective(prompt, directive) {
  const base = String(prompt ?? '').replace(/\s+$/u, '');
  const text = clean(directive);
  if (!text) return base;
  return [base, '', text].join('\n');
}

export function humanizeSelfRunPrompt(prompt) {
  const source=String(prompt??'').trim();
  if(!source)return source;
  const lines=source.split(/\r?\n/);
  const fields=new Map();
  const sections=new Map();
  let section='';
  for(const line of lines) {
    const heading=line.match(/^\[([^\]]+)\]\s*$/u);
    if(heading){section=heading[1];if(!sections.has(section))sections.set(section,[]);continue;}
    if(!section) {
      const field=line.match(/^([A-Z][A-Z0-9_]+)=(.*)$/u);
      if(field){fields.set(field[1],field[2]);continue;}
    }
    if(section)sections.get(section).push(line);
  }
  if(!fields.has('TASK_ID')||!fields.has('TURN_ID')||!fields.has('REQUEST_ID'))return source;
  const out=[];
  out.push(`SelfRun 작업을 진행해. 작업 ID는 ${fields.get('TASK_ID')}이고 현재 턴은 ${fields.get('TURN_ID')}, 요청 ID는 ${fields.get('REQUEST_ID')}야.`);
  const docs=[];
  if(fields.get('SELF_RUN_SKILL_DOCUMENT_ID'))docs.push(`SelfRun 운영문서 ${fields.get('SELF_RUN_SKILL_DOCUMENT_ID')}`);
  if(fields.get('REQUIREMENT_DOCUMENT_ID'))docs.push(`요구사항 문서 ${fields.get('REQUIREMENT_DOCUMENT_ID')}`);
  if(fields.get('RESULT_DOCUMENT_ID'))docs.push(`이번 결과 문서 ${fields.get('RESULT_DOCUMENT_ID')}`);
  if(docs.length)out.push(`먼저 ${docs.join(', ')}를 읽고 현재 상태와 phase를 확인해.`);
  if(fields.get('PREVIOUS_RESULT_DOCUMENT_ID'))out.push(`이전 결과 문서는 ${fields.get('PREVIOUS_RESULT_DOCUMENT_ID')}야.`);
  if(fields.get('PARALLEL_GROUP_ID'))out.push(`이 턴은 병렬 작업 그룹 ${fields.get('PARALLEL_GROUP_ID')}에 속해.`);
  if(fields.get('BRANCH_ID'))out.push(`병렬 분기 ${fields.get('BRANCH_ID')}의 목표는 ${fields.get('BRANCH_OBJECTIVE')||'현재 분기 목표'}야.`);
  if(fields.get('MUTATION_BOUNDARY'))out.push(`이 분기의 변경 범위는 ${fields.get('MUTATION_BOUNDARY')}이고 계획은 ${fields.get('BRANCH_PLAN')||'지정된 계획'}이야.`);
  if(fields.get('MERGED_FROM'))out.push(`이 턴은 ${fields.get('MERGED_FROM')} 결과를 합쳐서 이어가는 단계야.`);
  if(fields.get('REPAIR_TARGET_DOCUMENT_ID')) {
    let repair=`이 턴은 결과 문서 ${fields.get('REPAIR_TARGET_DOCUMENT_ID')}의 복구 작업이야.`;
    if(fields.get('REPAIR_REASON'))repair+=` 복구 사유는 ${fields.get('REPAIR_REASON')}야.`;
    if(fields.get('REPAIR_PROBLEMS'))repair+=` 확인할 문제는 ${fields.get('REPAIR_PROBLEMS')}야.`;
    out.push(repair);
  }
  const sectionText=name=>(sections.get(name)||[]).join('\n').trim();
  const next=sectionText('이전 턴에서 확정된 다음 입력');
  if(next)out.push(`이전 턴에서 확정된 다음 입력은 다음과 같아.\n${next}`);
  const intervention=sectionText('사용자 개입 결과·실제 상태 검증 필요');
  if(intervention)out.push(`사용자 개입 결과는 다음과 같아. 실제 상태를 확인해서 반영해.\n${intervention}`);
  const user=sectionText('사용자 추가 지시 원문');
  if(user)out.push(`추가 지시는 다음과 같아.\n${user}`);
  out.push('현재 phase에 필요한 실제 작업을 수행하고, 필요한 저장과 검증을 마친 뒤 이번 결과 문서를 최종 확정해.');
  return out.join('\n\n');
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
