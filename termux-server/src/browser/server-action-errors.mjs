// Only a verified local action result may establish that no click occurred.
// Error messages, serialized fields, and page exceptions cannot create proof.
const notSubmitted = new WeakSet(), stages = new WeakMap();
export const isActionNotSubmitted = error => !!error && typeof error === 'object' && notSubmitted.has(error);
export const actionNotSubmittedStage = error => stages.get(error) || null;
export function actionNotSubmittedError(stage='input') {
  const error=Object.assign(new Error('Submission preparation changed before click'),{released:false});
  notSubmitted.add(error);stages.set(error,stage);return error;
}
