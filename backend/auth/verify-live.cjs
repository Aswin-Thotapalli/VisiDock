// Generates verification links without sending email, using only a temporary account.
const {readFileSync}=require('node:fs');
const {randomUUID,randomBytes}=require('node:crypto');
const auth=require('firebase-tools/lib/auth');
const {Client}=require('firebase-tools/lib/apiv2');
const project='visidock-thotapalli';
const key=JSON.parse(readFileSync('app/google-services.json','utf8')).client[0].api_key[0].current_key;
let user;
async function api(action,body){const r=await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:${action}?key=${key}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const data=await r.json();if(!r.ok)throw new Error(`${action}: ${r.status} ${data.error?.message?.split(' : ')[0]}`);return data;}
(async()=>{try{
 const account=auth.getProjectDefaultAccount(process.cwd());if(!account)throw Error('Local Firebase login unavailable');auth.setActiveAccount({},account);
 const c=new Client({urlPrefix:'https://identitytoolkit.googleapis.com',auth:true});
 const config=(await c.get(`/admin/v2/projects/${project}/config`)).body;
 console.log(JSON.stringify({check:'callback',uri:config.notification?.sendEmail?.callbackUri}));
 const email=`visidock-auth-${randomUUID()}@example.invalid`;
 user=await api('signUp',{email,password:randomBytes(32).toString('base64url'),returnSecureToken:true});
 const generated=(await c.post('/v1/accounts:sendOobCode',{requestType:'VERIFY_EMAIL',email,targetProjectId:project,returnOobLink:true})).body;
 const url=new URL(generated.oobLink);
 console.log(JSON.stringify({check:'generated-link',origin:url.origin,path:url.pathname,mode:url.searchParams.get('mode'),hasApiKey:!!url.searchParams.get('apiKey'),hasCode:!!url.searchParams.get('oobCode'),parameterNames:[...url.searchParams.keys()]}));
 const page=await fetch(url);console.log(JSON.stringify({check:'hosted-handler',status:page.status,hasActionWidget:/fireauth|VisiDock/.test(await page.text())}));
 const {initializeApp,deleteApp}=await import('firebase/app');
 const {getAuth,checkActionCode,confirmPasswordReset}=await import('firebase/auth');
 const {inspectAction,readAction,readPastedAction}=await import('./action.mjs');
 // Use the key actually embedded in the email link, not merely the Android key.
 const linkKey=url.searchParams.get('apiKey');
 if(!linkKey)throw new Error('Generated link has no API key');
 console.log(JSON.stringify({check:'email-link-api-key',matchesAndroid:linkKey===key}));
 const sdk=initializeApp({apiKey:linkKey,projectId:project},'auth-probe');
 const authApi=getAuth(sdk);
 const checked=await inspectAction(readAction(url.href),{checkActionCode:code=>checkActionCode(authApi,code)});
 console.log(JSON.stringify({check:'handler-valid-verification',mode:checked.mode}));
 const repaired=await inspectAction(readPastedAction(url.href.replaceAll('&','&amp;')),{checkActionCode:code=>checkActionCode(authApi,code)});
 console.log(JSON.stringify({check:'pasted-escaped-link-recovery',mode:repaired.mode}));
 await api('update',{oobCode:url.searchParams.get('oobCode')});
 const lookup=await api('lookup',{idToken:user.idToken});console.log(JSON.stringify({check:'valid-code-applies',verified:lookup.users?.[0]?.emailVerified===true}));
 try{await api('update',{oobCode:url.searchParams.get('oobCode')});console.log(JSON.stringify({check:'used-code',rejected:false}));}catch{console.log(JSON.stringify({check:'used-code',rejected:true}));}
 try{await api('update',{oobCode:'malformed-test-code'});console.log(JSON.stringify({check:'malformed-code',rejected:false}));}catch{console.log(JSON.stringify({check:'malformed-code',rejected:true}));}
 const reset=(await c.post('/v1/accounts:sendOobCode',{requestType:'PASSWORD_RESET',email,targetProjectId:project,returnOobLink:true})).body;
 const resetAction=await inspectAction(readAction(reset.oobLink),{checkActionCode:code=>checkActionCode(authApi,code)});
 await confirmPasswordReset(authApi,resetAction.code,randomBytes(32).toString('base64url'));
 console.log(JSON.stringify({check:'handler-valid-password-reset',mode:resetAction.mode,success:true}));
 // Password reset revokes the original ID token; use the authenticated admin client for cleanup.
 user.adminCleanup=async()=>c.post('/v1/accounts:delete',{localId:user.localId,targetProjectId:project});
 await deleteApp(sdk);
 }catch(e){console.log(JSON.stringify({check:'failed',message:e instanceof Error&&/^(signUp|lookup|update):/.test(e.message)?e.message:'Auth verification probe failed'}));process.exitCode=1;}
 finally{if(user){try{if(user.adminCleanup)await user.adminCleanup();else await api('delete',{idToken:user.idToken});console.log(JSON.stringify({check:'temporary-account-cleanup',success:true}));}catch{console.log(JSON.stringify({check:'temporary-account-cleanup',success:false}));process.exitCode=1;}}}
})();
