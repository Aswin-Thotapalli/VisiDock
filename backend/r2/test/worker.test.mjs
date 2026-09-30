import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPair, SignJWT, exportJWK, createLocalJWKSet } from 'jose';
import { createWorker, verifyFirebaseToken, readManifest } from '../src/worker.mjs';

const jpeg = new Uint8Array([255,216,255,224]);

test('back images require their own owner manifest and remain private through their lifecycle', async () => {
  for (const file of ['back-original', 'back-preview.jpg']) {
    const field = file === 'back-original' ? 'backOriginalPath' : 'backImagePath';
    const f = fixture({state:{[field]:`users/alice/cards/one/${file}`}});
    assert.equal((await f.send('PUT',{file})).status,204);
    f.state.status='ready';
    const response=await f.send('GET',{file});
    assert.equal(response.status,200);
    assert.equal(response.headers.get('cache-control'),'private, no-store');
    f.state.status='deleting';
    assert.equal((await f.send('DELETE',{file})).status,204);
    const cross=fixture({state:{[field]:`users/bob/cards/one/${file}`}});
    assert.equal((await cross.send('PUT',{file})).status,404);
    assert.equal((await fixture().send('PUT',{file})).status,404);
  }
});
function fixture(options = {}) {
  const calls = [];
  const state = { status: 'uploading', imagePath: 'users/alice/cards/one/preview.jpg', originalPath: 'users/alice/cards/one/original', ...options.state };
  const worker = createWorker({ verify: async token => { if (token !== 'alice') throw Error('bad'); return token; }, manifest: async (token, project, uid, id) => { calls.push(['manifest', uid, id]); return state; }, ...options.dependencies });
  const env = { FIREBASE_PROJECT_ID: 'visidock-thotapalli', CARD_IMAGES: {
    put: async (...args) => calls.push(['put', ...args]), delete: async key => calls.push(['delete',key]),
    get: async key => { calls.push(['get',key]); return { body: jpeg, size: 4, httpMetadata: { contentType: 'image/jpeg' } }; },
  } };
  const send = (method = 'PUT', settings = {}) => worker.fetch(new Request(`https://images.example/v1/cards/one/${settings.file || 'preview.jpg'}`, { method, headers: { Authorization: 'Bearer alice', 'Content-Type': 'image/jpeg', ...settings.headers }, ...(['PUT'].includes(method) ? { body: settings.body ?? jpeg, duplex: 'half' } : {}) }),env);
  return { send, calls, worker, env, state };
}

test('authenticated upload derives owner key and stores private metadata', async () => {
  const f = fixture(); assert.equal((await f.send()).status,204);
  const write = f.calls.find(c => c[0] === 'put');
  assert.equal(write[1],'users/alice/cards/one/preview.jpg');
  assert.equal(write[3].httpMetadata.cacheControl,'private, no-store');
  assert.equal(f.calls.filter(c => c[0] === 'manifest').length,3);
});
test('missing and invalid tokens cannot reach Firestore or R2', async () => {
  for (const value of ['', 'Bearer bob', 'Basic alice']) {
    const f = fixture(); assert.equal((await f.send('PUT',{headers:{Authorization:value}})).status,401); assert.equal(f.calls.length,0);
  }
});
test('status gates each operation and cross-user manifest paths cannot access R2', async () => {
  for (const method of ['GET','PUT','DELETE']) {
    const f = fixture({state:{status: {GET:'uploading',PUT:'ready',DELETE:'ready'}[method]}});
    assert.equal((await f.send(method)).status,409); assert.equal(f.calls.some(c => c[0] !== 'manifest'),false);
    const other = fixture({state:{status:{GET:'ready',PUT:'uploading',DELETE:'deleting'}[method],imagePath:'users/bob/cards/one/preview.jpg'}});
    assert.equal((await other.send(method)).status,404); assert.equal(other.calls.some(c => c[0] !== 'manifest'),false);
  }
});
test('ready reads use private no-store responses; deleting deletes idempotently', async () => {
  const f = fixture({state:{status:'ready'}}); const res = await f.send('GET'); assert.equal(res.status,200);
  assert.equal(res.headers.get('cache-control'),'private, no-store'); assert.deepEqual(new Uint8Array(await res.arrayBuffer()),jpeg);
  f.state.status='deleting'; assert.equal((await f.send('DELETE')).status,204); assert.equal((await f.send('DELETE')).status,204);
});
test('unrecognized paths, identifiers, query parameters and methods fail closed', async () => {
  const f=fixture();
  for (const path of ['/v1/cards/one/extra','/v1/cards/a%2Fb/original','/v1/cards/one/original?uid=bob','/users/bob/cards/one/original']) assert.equal((await f.worker.fetch(new Request(`https://images.example${path}`),f.env)).status,404);
  assert.equal((await f.send('POST')).status,405); assert.equal(f.calls.length,0);
});
test('type and content signature must match, originals support PNG and WebP', async () => {
  for (const settings of [{headers:{'Content-Type':'text/html'}},{body:new Uint8Array([1,2,3])},{headers:{'Content-Type':'image/png'}}]) assert.equal((await fixture().send('PUT',settings)).status,415);
  for(const [type, bytes] of [['image/png',[137,80,78,71,13,10,26,10]],['image/webp',[82,73,70,70,0,0,0,0,87,69,66,80]]]) {
    assert.equal((await fixture().send('PUT',{file:'original',headers:{'Content-Type':type},body:new Uint8Array(bytes)})).status,204);
  }
});
test('empty and oversized bodies including dishonest content lengths are rejected before R2 writes', async () => {
  assert.equal((await fixture().send('PUT',{body:new Uint8Array()})).status,400);
  for(const [file,limit] of [['preview.jpg',6*1024*1024],['original',20*1024*1024]]) {
    const f=fixture(); assert.equal((await f.send('PUT',{file,headers:{'Content-Length':String(limit+1)}})).status,413);
    const body = new Uint8Array(limit+1); body.set(jpeg);
    assert.equal((await f.send('PUT',{file,headers:{'Content-Length':'1'},body})).status,413);
    assert.equal(f.calls.some(c=>c[0]==='put'),false);
    assert.equal((await fixture().send('PUT',{file,body:body.slice(0,limit)})).status,204);
  }
});
test('manifest recheck catches deletion during body upload; errors do not disclose internals', async () => {
  let count=0; const f=fixture({dependencies:{manifest:async()=>({status:++count===1?'uploading':'deleting',imagePath:'users/alice/cards/one/preview.jpg'})}});
  assert.equal((await f.send()).status,409); assert.equal(f.calls.length,0);
  const broken=fixture({dependencies:{manifest:async()=>{throw Error('secret backend credential')}}});
  const response=await broken.send(); assert.equal(response.status,503); assert.equal((await response.text()).includes('secret'),false);
});
test('deletion or unavailable manifest after PUT compensates by deleting the new object', async () => {
  for(const finalState of ['deleting','missing','wrong-path']) {
    let reads=0;
    const f=fixture({dependencies:{manifest:async()=>{
      reads++;
      if(reads===3 && finalState==='missing') throw Error('Gone');
      return {status:reads===3 && finalState==='deleting'?'deleting':'uploading',imagePath:reads===3 && finalState==='wrong-path'?'other':'users/alice/cards/one/preview.jpg'};
    }}});
    const response=await f.send(); assert.equal(response.status,finalState==='missing'?503:409);
    assert.deepEqual(f.calls.map(c=>c[0]),['put','delete']); assert.equal(f.calls[1][1],'users/alice/cards/one/preview.jpg');
  }
});
test('Firestore manifest uses same bearer and owner path; rejects denied or unavailable backend', async () => {
  const result=await readManifest('token','visidock-thotapalli','alice','one',async(url,init)=>{
    assert.equal(url,'https://firestore.googleapis.com/v1/projects/visidock-thotapalli/databases/(default)/documents/users/alice/cards/one');
    assert.equal(init.headers.Authorization,'Bearer token'); return Response.json({fields:{status:{stringValue:'ready'}}});
  }); assert.equal(result.status,'ready');
  for(const status of [401,403,404,500]) await assert.rejects(readManifest('token','project','alice','one',async()=>new Response(null,{status})),error=>error.status===(status===500?503:404));
});
test('real RS256 verification rejects wrong audience, issuer, expiry, subject, time and signature', async () => {
  const {privateKey,publicKey}=await generateKeyPair('RS256'); const jwk=await exportJWK(publicKey); jwk.kid='test-key';
  const keys=createLocalJWKSet({keys:[jwk]}); const now=Math.floor(Date.now()/1000);
  const sign=async changes=>new SignJWT({aud:'visidock-thotapalli',iss:'https://securetoken.google.com/visidock-thotapalli',sub:'alice',iat:now,exp:now+600,auth_time:now,...changes}).setProtectedHeader({alg:'RS256',kid:'test-key'}).sign(privateKey);
  assert.equal(await verifyFirebaseToken(await sign({}),'visidock-thotapalli',keys),'alice');
  for(const change of [{aud:'another-project'},{iss:'https://attacker.example'},{exp:now-1},{sub:'../bob'},{iat:now+600},{auth_time:now+600},{auth_time:undefined},{exp:undefined}]) await assert.rejects(verifyFirebaseToken(await sign(change),'visidock-thotapalli',keys));
  const other=await generateKeyPair('RS256'); const forged=await new SignJWT({aud:'visidock-thotapalli'}).setProtectedHeader({alg:'RS256',kid:'test-key'}).sign(other.privateKey);
  await assert.rejects(verifyFirebaseToken(forged,'visidock-thotapalli',keys));
});
