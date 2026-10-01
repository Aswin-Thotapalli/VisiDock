// Explicit live integration harness. Never imported by the local unit-test glob.
import { randomBytes, randomUUID } from 'node:crypto';

const api = process.env.IMAGE_API_URL;
const apiKey = process.env.FIREBASE_WEB_API_KEY;
const project = 'visidock-thotapalli';
const accounts = [];
const results = [];
let failure = false;
const report = (step, status, httpStatus) => {
  const item = { step, status, ...(httpStatus === undefined ? {} : { httpStatus }) };
  results.push(item);
  console.log(JSON.stringify(item));
};
// Errors deliberately contain only fixed operation labels/status codes.
class CheckFailure extends Error { constructor(step, status) { super(step); this.step = step; this.status = status; } }
async function request(step, url, options, expected = [200]) {
  let response;
  try { response = await fetch(url, { ...options, signal: AbortSignal.timeout(20000), redirect: 'error' }); }
  catch { throw new CheckFailure(step); }
  if (!expected.includes(response.status)) throw new CheckFailure(step, response.status);
  report(step, 'PASS', response.status);
  return response;
}
const bearer = account => ({ Authorization: `Bearer ${account.token}` });
const jsonHeaders = account => ({ ...bearer(account), 'Content-Type': 'application/json' });
const documentUrl = account => `https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/documents/users/${encodeURIComponent(account.uid)}/cards/${account.cardId}`;
const objectUrl = (account, file) => `${api.replace(/\/$/, '')}/v1/cards/${account.cardId}/${file}`;
const images = [
  { file: 'original', type: 'image/png', bytes: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64') },
  { file: 'preview.jpg', type: 'image/jpeg', bytes: Buffer.from('/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD9U6KKKAP/2Q==', 'base64') },
];
images.push(...images.map(image => ({ ...image, file: `back-${image.file}` })));
async function signup(label) {
  const response = await request(`${label}: create temporary account`, `https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${encodeURIComponent(apiKey)}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: `visidock-smoke-${randomUUID()}@example.invalid`, password: randomBytes(32).toString('base64url'), returnSecureToken: true }),
  });
  let data;
  try { data = await response.json(); } catch { throw new CheckFailure(`${label}: account response`); }
  if (!data.idToken || !data.localId) throw new CheckFailure(`${label}: account response`);
  const account = { label, token: data.idToken, uid: data.localId, cardId: `smoke-${randomUUID()}`, manifestAttempted: false };
  accounts.push(account);
  return account;
}
async function createManifest(account) {
  const path = `users/${account.uid}/cards/${account.cardId}`;
  const values = { name: 'Temporary image integration test', role: '', company: '', phone: '', email: '', address: '', website: '', notes: '', rawText: '', imagePath: `${path}/preview.jpg`, originalPath: `${path}/original`, favorite: false, createdAt: Date.now(), uploadStartedAt: Date.now(), status: 'uploading' };
  Object.assign(values, {backImagePath:`${path}/back-preview.jpg`,backOriginalPath:`${path}/back-original`,backRawText:'Synthetic reverse side',sourceScanId:randomUUID()});
  const fields = Object.fromEntries(Object.entries(values).map(([key, value]) => [key, typeof value === 'boolean' ? { booleanValue: value } : typeof value === 'number' ? { integerValue: String(value) } : { stringValue: value }]));
  account.manifestAttempted = true;
  await request(`${account.label}: create uploading manifest`, `${documentUrl(account)}?currentDocument.exists=false`, { method: 'PATCH', headers: jsonHeaders(account), body: JSON.stringify({ fields }) });
}
async function setStatus(account, status, prefix = account.label) {
  await request(`${prefix}: mark ${status}`, `${documentUrl(account)}?updateMask.fieldPaths=status&currentDocument.exists=true`, { method: 'PATCH', headers: jsonHeaders(account), body: JSON.stringify({ fields: { status: { stringValue: status } } }) });
}
async function cleanup(account) {
  const attempt = async action => {
    try { await action(); } catch (error) { failure = true; report(error instanceof CheckFailure ? error.step : `${account.label}: cleanup operation`, 'CLEANUP_FAILED', error instanceof CheckFailure ? error.status : undefined); }
  };
  if (account.manifestAttempted && !account.manifestDeleted) {
    await attempt(() => setStatus(account, 'deleting', `${account.label} cleanup`));
    let allImagesRemoved = true;
    let files=[];
    try {
      const manifest=(await (await request(`${account.label} cleanup: read manifest`,documentUrl(account),{headers:bearer(account)})).json()).fields;
      const records=[manifest,manifest.previousRecord?.mapValue?.fields].filter(Boolean);
      files=[...new Set(records.flatMap(record=>['imagePath','originalPath','backImagePath','backOriginalPath'].map(field=>record[field]?.stringValue).filter(Boolean).map(path=>path.split('/').at(-1))))];
    } catch(error) {allImagesRemoved=false;failure=true;report(error.step || `${account.label}: cleanup manifest`,'CLEANUP_FAILED',error.status);}
    for (const file of files) {
      try { await request(`${account.label} cleanup: delete ${file}`, objectUrl(account,file), { method:'DELETE', headers:bearer(account) }, [204]); }
      catch (error) { allImagesRemoved=false; failure=true; report(error.step || `${account.label}: cleanup image`, 'CLEANUP_FAILED',error.status); }
    }
    // Preserve the deleting manifest if object cleanup fails, so cleanup can be retried.
    if (allImagesRemoved) await attempt(async () => { await request(`${account.label} cleanup: delete manifest`,documentUrl(account),{method:'DELETE',headers:bearer(account)},[200,404]); account.manifestDeleted=true; });
  }
  if (account.manifestAttempted && !account.manifestDeleted) {
    failure = true;
    console.log(JSON.stringify({ step: 'Manual test cleanup required', uid: account.uid, cardId: account.cardId }));
    return;
  }
  await attempt(() => request(`${account.label} cleanup: delete temporary account`, `https://identitytoolkit.googleapis.com/v1/accounts:delete?key=${encodeURIComponent(apiKey)}`, { method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({idToken:account.token}) }));
}

try {
  if (!api || !apiKey) throw new CheckFailure('configuration requires IMAGE_API_URL and FIREBASE_WEB_API_KEY');
  let parsed;
  try { parsed = new URL(api); } catch { throw new CheckFailure('IMAGE_API_URL must be an HTTPS origin'); }
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.search || parsed.hash || !['','/'].includes(parsed.pathname)) throw new CheckFailure('IMAGE_API_URL must be an HTTPS origin');
  const a = await signup('Account A');
  const b = await signup('Account B');
  for (const account of [a,b]) {
    await createManifest(account);
    for (const image of images) await request(`${account.label}: upload ${image.file}`,objectUrl(account,image.file),{method:'PUT',headers:{...bearer(account),'Content-Type':image.type},body:image.bytes},[204]);
    await setStatus(account,'ready');
    for (const image of images) {
      const response=await request(`${account.label}: read ${image.file}`,objectUrl(account,image.file),{headers:bearer(account)});
      if (!Buffer.from(await response.arrayBuffer()).equals(image.bytes)) throw new CheckFailure(`${account.label}: verify ${image.file} bytes`);
      report(`${account.label}: verify ${image.file} bytes`,'PASS');
    }
  }
  // Keep the old manifest until final cleanup so both revisions remain discoverable on failure.
  const revision=randomUUID().replaceAll('-','');
  const replacements=images.filter(image=>!image.file.startsWith('back-')).map(image=>({...image,file:image.file==='original'?`original-r${revision}`:`preview-r${revision}.jpg`}));
  images.push(...replacements);
  for(const account of [a,b]) {
    const previous=(await (await request(`${account.label}: read previous manifest`,documentUrl(account),{headers:bearer(account)})).json()).fields;
    const prefix=`users/${account.uid}/cards/${account.cardId}/`;
    const fields={...previous,status:{stringValue:'uploading'},sourceScanId:{stringValue:randomUUID()},uploadStartedAt:{integerValue:String(Date.now())},previousRecord:{mapValue:{fields:previous}},
      originalPath:{stringValue:prefix+replacements[0].file},imagePath:{stringValue:prefix+replacements[1].file}};
    await request(`${account.label}: begin front replacement`,documentUrl(account),{method:'PATCH',headers:jsonHeaders(account),body:JSON.stringify({fields})});
    await request(`${account.label}: old front readable during replacement`,objectUrl(account,'preview.jpg'),{headers:bearer(account)});
    await request(`${account.label}: old front protected from overwrite`,objectUrl(account,'preview.jpg'),{method:'PUT',headers:{...bearer(account),'Content-Type':'image/jpeg'},body:images[1].bytes},[404]);
    for(const image of replacements) await request(`${account.label}: upload revision ${image.file}`,objectUrl(account,image.file),{method:'PUT',headers:{...bearer(account),'Content-Type':image.type},body:image.bytes},[204]);
    await setStatus(account,'ready');
    for(const image of replacements) {
      const response=await request(`${account.label}: read revision ${image.file}`,objectUrl(account,image.file),{headers:bearer(account)});
      if(!Buffer.from(await response.arrayBuffer()).equals(image.bytes)) throw new CheckFailure(`${account.label}: revision bytes`);
    }
    await request(`${account.label}: unchanged back retained`,objectUrl(account,'back-preview.jpg'),{headers:bearer(account)});
  }
  for (const image of images) {
    await request(`Isolation: B cannot read A ${image.file}`,objectUrl(a,image.file),{headers:bearer(b)},[404]);
    await request(`Authentication: missing token ${image.file}`,objectUrl(a,image.file),{},[401]);
  }
  for (const account of [a,b]) {
    await setStatus(account,'deleting');
    for (const image of images) {
      await request(`${account.label}: delete ${image.file}`,objectUrl(account,image.file),{method:'DELETE',headers:bearer(account)},[204]);
      await request(`${account.label}: deleted ${image.file} cannot be read`,objectUrl(account,image.file),{headers:bearer(account)},[409]);
    }
    await request(`${account.label}: delete manifest`,documentUrl(account),{method:'DELETE',headers:bearer(account)});
    account.manifestDeleted=true;
  }
} catch (error) {
  failure=true;
  report(error instanceof CheckFailure ? error.step : 'Integration check', 'FAIL',error instanceof CheckFailure ? error.status : undefined);
} finally {
  for (const account of accounts.reverse()) await cleanup(account);
  console.log(JSON.stringify({ result: failure?'FAILED':'PASSED', checks:results.filter(r=>r.status==='PASS').length, failures:results.filter(r=>r.status!=='PASS').length, credentialsLogged:false }));
  process.exitCode=failure?1:0;
}
