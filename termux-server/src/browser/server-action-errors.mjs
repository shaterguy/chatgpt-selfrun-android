// Only a verified local action result may establish that no click occurred.
// Error messages, serialized fields, and page exceptions cannot create proof.
const notSubmitted = new WeakSet();
export const isActionNotSubmitted = error => !!error && typeof error === 'object' && notSubmitted.has(error);
export function actionNotSubmittedError() {
  const error=Object.assign(new Error('Submission preparation changed before click'),{released:false});
  notSubmitted.add(error);return error;
}
