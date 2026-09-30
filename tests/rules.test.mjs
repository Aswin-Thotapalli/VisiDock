import { before, after, beforeEach, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, updateDoc, deleteDoc, getDocs, collection } from 'firebase/firestore';
import { ref, uploadBytes, getBytes, deleteObject } from 'firebase/storage';

let env;
const card = (uid='alice', id='one') => ({name:'Test Person',role:'Engineer',company:'Example',phone:'',email:'',address:'',website:'',notes:'',rawText:'',favorite:false,createdAt:Date.now(),status:'ready',imagePath:`users/${uid}/cards/${id}/preview.jpg`,originalPath:`users/${uid}/cards/${id}/original`});
before(async () => { env = await initializeTestEnvironment({projectId:'demo-visidock',firestore:{rules:readFileSync('firestore.rules','utf8')},storage:{rules:readFileSync('storage.rules','utf8')}}); });
beforeEach(async () => { await env.clearFirestore(); await env.clearStorage(); });
after(async () => { await env?.cleanup(); });
const db = uid => env.authenticatedContext(uid).firestore();

test('two-sided cards keep owner paths and scan grouping immutable', async () => {
  const target=doc(db('alice'),'users/alice/cards/one');
  const value={...card(),backImagePath:'users/alice/cards/one/back-preview.jpg',backOriginalPath:'users/alice/cards/one/back-original',backRawText:'Reverse text',sourceScanId:'11111111-1111-1111-1111-111111111111'};
  await assertSucceeds(setDoc(target,value));
  await assertSucceeds(updateDoc(target,{notes:'Two people on the same card'}));
  await assertFails(updateDoc(target,{backImagePath:'users/bob/cards/one/back-preview.jpg'}));
  await assertFails(updateDoc(target,{sourceScanId:'another-scan'}));
  await assertFails(updateDoc(target,{backRawText:'x'.repeat(12001)}));
  await assertFails(setDoc(doc(db('alice'),'users/alice/cards/two'),{...card('alice','two'),backImagePath:'users/alice/cards/two/back-preview.jpg'}));
});

test('owner can create, read and edit a valid card', async () => {
  const target=doc(db('alice'),'users/alice/cards/one');
  await assertSucceeds(setDoc(target,card()));
  await assertSucceeds(getDoc(target));
  await assertSucceeds(updateDoc(target,{notes:'Updated'}));
});
test('optional labeled phone lists preserve legacy clients and enforce every entry', async () => {
  const target=doc(db('alice'),'users/alice/cards/one');
  await assertSucceeds(setDoc(target,card())); // Old documents have no list.
  const phones=[{number:'+91 98765 43210',label:'Mobile'},{number:'040 2345 6789',label:'Office'},{number:'1800 123 456',label:'Support'}];
  await assertSucceeds(updateDoc(target,{phones,phone:phones[0].number}));
  await assertSucceeds(updateDoc(target,{phones:Array.from({length:12},(_,i)=>({number:String(i%10).repeat(80),label:'x'.repeat(40)}))}));
  const full=doc(db('alice'),'users/alice/cards/full');
  await assertSucceeds(setDoc(full,{...card('alice','full'),phones:Array(12).fill(phones[0]),backImagePath:'users/alice/cards/full/back-preview.jpg',backOriginalPath:'users/alice/cards/full/back-original',sourceScanId:'11111111-1111-1111-1111-111111111111'}));
  await assertSucceeds(updateDoc(full,{notes:'All twelve numbers and both sides remain editable'}));
  for(const values of ['not-a-list',{},null,Array(13).fill(phones[0]),[{number:'',label:''}],[{number:'x'.repeat(81),label:''}],[{number:'123',label:'x'.repeat(41)}],[{number:123,label:''}],[{number:'123',label:1}],[{number:'123'}],[{number:'123',label:'',extra:true}],['123'],[null]]) {
    await assertFails(updateDoc(target,{phones:values}));
  }
  // Invalid data in the last allowed slot must not bypass validation.
  await assertFails(updateDoc(target,{phones:[...Array(11).fill(phones[0]),{number:'123',label:42}]}));
  await assertSucceeds(updateDoc(target,{phones:[],phone:''}));
  await assertFails(updateDoc(target,{phone:'x'.repeat(101)}));
});
test('another user cannot read, write or delete the owner card', async () => {
  await setDoc(doc(db('alice'),'users/alice/cards/one'),card());
  const target=doc(db('bob'),'users/alice/cards/one');
  await assertFails(getDoc(target)); await assertFails(setDoc(target,card())); await assertFails(deleteDoc(target));
});
test('anonymous requests cannot access cards', async () => {
  const target=doc(env.unauthenticatedContext().firestore(),'users/alice/cards/one');
  await assertFails(getDoc(target)); await assertFails(setDoc(target,card()));
});
test('types, lengths, ownership paths, fields and future timestamps are validated', async () => {
  const target=doc(db('alice'),'users/alice/cards/one');
  for(const change of [{notes:42},{notes:'x'.repeat(4001)},{favorite:'yes'},{role:'x'.repeat(301)},{imagePath:'users/bob/cards/one/preview.jpg'},{extra:true},{createdAt:Date.now()+86400000},{name:'',company:''},{status:'unknown'}]) {
    await assertFails(setDoc(target,{...card(),...change}));
  }
  const incomplete=card(); delete incomplete.email; await assertFails(setDoc(target,incomplete));
});
test('company-only cards can be saved and edited without inventing a person', async () => {
  const target=doc(db('alice'),'users/alice/cards/company-only');
  await assertSucceeds(setDoc(target,{...card('alice','company-only'),name:'',company:'Example Studio'}));
  await assertSucceeds(updateDoc(target,{notes:'Business contact'}));
  await assertFails(updateDoc(target,{company:''}));
  await assertFails(updateDoc(target,{company:'x'.repeat(301)}));
  await assertSucceeds(updateDoc(target,{name:'Named Person',company:''}));
});
test('saved ownership paths and creation date are immutable', async () => {
  const target=doc(db('alice'),'users/alice/cards/one'); await setDoc(target,card());
  await assertFails(updateDoc(target,{createdAt:1}));
  await assertFails(updateDoc(target,{imagePath:'',originalPath:''}));
});
test('delete requires a durable deleting manifest', async () => {
  const target=doc(db('alice'),'users/alice/cards/one'); await setDoc(target,card());
  await assertFails(deleteDoc(target)); await assertSucceeds(updateDoc(target,{status:'deleting'})); await assertSucceeds(deleteDoc(target));
});
test('a stale client cannot resurrect a card during deletion', async () => {
  const target=doc(db('alice'),'users/alice/cards/one'); const saved=card();
  await setDoc(target,saved); await updateDoc(target,{status:'deleting'});
  await assertFails(setDoc(target,{...saved,status:'ready'}));
  await assertFails(updateDoc(target,{status:'uploading'}));
});
test('upload manifest allows completion and rejects malformed upload age', async () => {
  const target=doc(db('alice'),'users/alice/cards/one');
  await assertFails(setDoc(target,{...card(),status:'uploading',uploadStartedAt:'yesterday'}));
  await assertSucceeds(setDoc(target,{...card(),status:'uploading',uploadStartedAt:Date.now()}));
  await assertSucceeds(updateDoc(target,{status:'ready'}));
  await assertFails(updateDoc(target,{status:'uploading'}));
});
test('collection queries remain isolated per user', async () => {
  await setDoc(doc(db('alice'),'users/alice/cards/one'),card());
  await assertSucceeds(getDocs(collection(db('alice'),'users/alice/cards')));
  await assertFails(getDocs(collection(db('bob'),'users/alice/cards')));
  await assertFails(getDocs(collection(env.unauthenticatedContext().firestore(),'users/alice/cards')));
});
test('owner can store and delete preview and original, other users cannot access either', async () => {
  for(const file of ['preview.jpg','original']) {
    const path=`users/alice/cards/one/${file}`;
    const own=ref(env.authenticatedContext('alice').storage(),path);
    await assertSucceeds(uploadBytes(own,new Uint8Array([1,2,3]),{contentType:'image/jpeg'}));
    await assertSucceeds(getBytes(own));
    const other=ref(env.authenticatedContext('bob').storage(),path);
    await assertFails(getBytes(other)); await assertFails(uploadBytes(other,new Uint8Array([1]),{contentType:'image/jpeg'})); await assertFails(deleteObject(other));
    await assertFails(getBytes(ref(env.unauthenticatedContext().storage(),path)));
    await assertSucceeds(deleteObject(own));
  }
});
test('unexpected image paths and file types are denied', async () => {
  const storage=env.authenticatedContext('alice').storage();
  await assertFails(uploadBytes(ref(storage,'users/alice/cards/one/executable'),new Uint8Array([1]),{contentType:'image/jpeg'}));
  await assertFails(uploadBytes(ref(storage,'users/alice/cards/one/preview.jpg'),new Uint8Array([1]),{contentType:'image/png'}));
  await assertFails(uploadBytes(ref(storage,'users/alice/cards/one/original'),new Uint8Array([1]),{contentType:'text/html'}));
});
test('oversized preview and original uploads are rejected', async () => {
  const storage=env.authenticatedContext('alice').storage();
  await assertFails(uploadBytes(ref(storage,'users/alice/cards/one/preview.jpg'),new Uint8Array(6*1024*1024),{contentType:'image/jpeg'}));
  await assertFails(uploadBytes(ref(storage,'users/alice/cards/one/original'),new Uint8Array(20*1024*1024+1),{contentType:'image/jpeg'}));
});
