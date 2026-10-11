// Each fixture represents one extension storage origin. Multicontroller tests
// share one instance; single-controller fixtures use their own origin.
export function fixtureNavigator({held=false}={}) {
  let owner=held;
  return {locks:{async request(name,{ifAvailable},callback){
    if(owner){if(!ifAvailable)throw Error('Fixture only supports nonqueued lock acquisition');return callback(null);}
    owner=true;
    try{return await callback({name,mode:'exclusive'});}finally{owner=false;}
  }}};
}
