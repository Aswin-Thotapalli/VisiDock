import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readAction,readPastedAction,inspectAction,actionError} from './action.mjs';
test('complete supported links and missing mode inferred from verified operation',async()=>{
 for(const [mode,operation] of [['verifyEmail','VERIFY_EMAIL'],['resetPassword','PASSWORD_RESET'],['recoverEmail','RECOVER_EMAIL']]){
  const action=readAction(`https://example.test/?mode=${mode}&oobCode=abcdefgh_123`);
  assert.equal((await inspectAction(action,{checkActionCode:async()=>({operation})})).mode,mode);
  assert.equal((await inspectAction({...action,mode:null},{checkActionCode:async()=>({operation})})).mode,mode);
 }
});
test('missing malformed duplicated and mismatched actions fail closed',async()=>{
 for(const query of ['', '?mode=verifyEmail','?oobCode=short','?oobCode=abcdefgh&mode=unknown','?oobCode=abcdefgh&oobCode=ijklmnop']) assert.throws(()=>readAction(`https://example.test/${query}`));
 await assert.rejects(inspectAction({mode:'verifyEmail',code:'abcdefgh'},{checkActionCode:async()=>({operation:'PASSWORD_RESET'})}));
});
test('expired invalid and network states have actionable messages without raw details',()=>{
 assert.match(actionError({code:'auth/expired-action-code'}),/expired/);
 assert.match(actionError({code:'auth/invalid-action-code'}),/already been used/);
 assert.match(actionError({code:'auth/network-request-failed'}),/connection/);
 assert.equal(actionError({message:'secret@example.com'}).includes('secret'),false);
});
test('pasted email links repair HTML separators without following unknown origins',async()=>{
 const repaired=readPastedAction(' https://visidock-thotapalli.firebaseapp.com/__/auth/action?mode=verifyEmail&amp;oobCode=abcdefgh_123&amp;apiKey=public-key ');
 assert.equal(repaired.code,'abcdefgh_123');
 const missingMode=readPastedAction('https://visidock-thotapalli.web.app/auth?oobCode=abcdefgh_123');
 assert.equal((await inspectAction(missingMode,{checkActionCode:async()=>({operation:'VERIFY_EMAIL'})})).mode,'verifyEmail');
 for(const value of ['https://attacker.example/?oobCode=abcdefgh_123','http://visidock-thotapalli.web.app/auth?oobCode=abcdefgh_123','https://visidock-thotapalli.web.app@attacker.example/auth?oobCode=abcdefgh_123','https://visidock-thotapalli.web.app:444/auth?oobCode=abcdefgh_123','https://visidock-thotapalli.web.app/other?oobCode=abcdefgh_123','javascript:alert(1)']) assert.throws(()=>readPastedAction(value));
});
