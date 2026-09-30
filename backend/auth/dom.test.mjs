import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { parseHTML } from 'linkedom';
import { readAction, readPastedAction, inspectAction, actionError } from './action.mjs';

const html=readFileSync(new URL('./public/index.html',import.meta.url),'utf8');
const source=readFileSync(new URL('./main.mjs',import.meta.url),'utf8').replace(/^import .*;\r?\n/gm,'');
const AsyncFunction=Object.getPrototypeOf(async function(){}).constructor;
async function page({mode='VERIFY_EMAIL',href='https://visidock-thotapalli.web.app/auth',error}={}) {
  const {document}=parseHTML(html); const handlers={}; const applied=[]; const history=[];
  for(const id of ['action-form','recovery-form']) document.getElementById(id).addEventListener=(type,handler)=>{handlers[id]=handler;};
  const run=new AsyncFunction('document','location','history','config','initializeApp','getAuth','checkActionCode','applyActionCode','confirmPasswordReset','readAction','readPastedAction','inspectAction','actionError',source);
  await run(document,{href,pathname:'/auth'},{replaceState:(_,__,url)=>history.push(url)},{},()=>({}),()=>({}),async()=>{if(error)throw error;return {operation:mode};},async(_,code)=>applied.push(['apply',code]),async(_,code,password)=>applied.push(['reset',code,password]),readAction,readPastedAction,inspectAction,actionError);
  return {document,history,applied,submit:id=>handlers[id]({preventDefault(){}})};
}
test('missing-link page offers paste recovery and does not expose codes in history',async()=>{
  const p=await page(); assert.equal(p.document.getElementById('recovery-form').hidden,false);
  p.document.getElementById('email-link').value='https://visidock-thotapalli.firebaseapp.com/__/auth/action?mode=verifyEmail&amp;oobCode=abcdefgh_123';
  await p.submit('recovery-form');
  assert.equal(p.document.getElementById('email-link').value,'');
  assert.equal(p.document.getElementById('action-form').hidden,false);
  assert.equal(p.document.querySelector('h1').textContent,'Make it official.');
  await p.submit('action-form');
  assert.deepEqual(p.applied,[['apply','abcdefgh_123']]);
  assert.equal(p.document.querySelector('h1').textContent,'You’re verified.');
  assert.deepEqual(p.history,['/auth']);
});
test('password reset blocks mismatched passwords and clears accepted password',async()=>{
  const p=await page({mode:'PASSWORD_RESET',href:'https://visidock-thotapalli.web.app/auth?mode=resetPassword&oobCode=abcdefgh_123'});
  const password=p.document.getElementById('password'),confirm=p.document.getElementById('confirm');
  password.value='Test-password-123';confirm.value='Different-password';
  await p.submit('action-form');assert.equal(p.applied.length,0);assert.match(p.document.getElementById('status').textContent,/do not match/);
  confirm.value=password.value;await p.submit('action-form');
  assert.equal(p.applied[0][0],'reset');assert.equal(password.value,'');assert.equal(confirm.value,'');
  assert.equal(p.document.querySelector('h1').textContent,'Password updated.');
});
test('expired links explain failure and allow a fresh pasted link',async()=>{
  const p=await page({href:'https://visidock-thotapalli.web.app/auth?mode=verifyEmail&oobCode=abcdefgh_123',error:{code:'auth/expired-action-code'}});
  assert.match(p.document.getElementById('description').textContent,/expired/);
  assert.equal(p.document.getElementById('recovery-form').hidden,false);assert.equal(p.applied.length,0);
});
test('email recovery applies only after explicit confirmation',async()=>{
  const p=await page({mode:'RECOVER_EMAIL',href:'https://visidock-thotapalli.web.app/auth?mode=recoverEmail&oobCode=abcdefgh_123'});
  assert.equal(p.applied.length,0);assert.equal(p.document.querySelector('h1').textContent,'Restore your email.');
  await p.submit('action-form');assert.equal(p.applied[0][0],'apply');assert.equal(p.document.querySelector('h1').textContent,'Email restored.');
});
