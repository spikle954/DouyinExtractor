import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
const source=await fs.readFile(new URL('../core/src/main/kotlin/io/github/douyinextractor/core/browser/BrowserCaptureScript.kt', import.meta.url),'utf8');
const script=source.match(/val javascript: String = """([\s\S]*?)"""\.trimIndent\(\)/)?.[1].trim();
assert.ok(script,'Kotlin source fixed triplequoted expression present');
const compiled=new vm.Script(script);
const ID='1234567890123456789';
class Element {
 constructor(attrs={},text=''){this.attrs=attrs;this.textContent=text;this.style={display:'block',visibility:'visible',opacity:'1'};this.rect={width:240,height:400,top:10,left:10,bottom:410,right:250};this.parentElement=null;this.sources=[];this.children=[];this.tagName='DIV';}
 getAttribute(k){return this.attrs[k]??null;}
 getBoundingClientRect(){return this.rect;}
 querySelectorAll(){return this.sources;}
}
function setup(){
 let cookieReads=0,fetches=0;
 const primary=new Element({'data-aweme-id':ID,type:'video/mp4'});
 Object.assign(primary,{currentSrc:'https://synthetic.douyinvod.com/video.mp4',src:'https://synthetic.douyinvod.com/video.mp4',poster:'https://synthetic.douyinpic.com/cover.jpg',videoWidth:720,videoHeight:1280,duration:7.5,readyState:3,mediaKeys:null});
 const nodes={videos:[primary],canonicals:[{href:'https://www.douyin.com/video/'+ID}],verification:[],login:[],playbackErrors:[]};
 const document={title:'合成测试标题',readyState:'complete',documentElement:{outerHTML:'<html><body><video></video></body></html>'},querySelectorAll(selector){if(selector==='video')return nodes.videos;if(selector==='link[rel="canonical"]')return nodes.canonicals;if(selector.includes('captcha'))return nodes.verification;if(selector.includes('login-modal'))return nodes.login;if(selector.includes('[role="alert"]'))return nodes.playbackErrors.filter(e=>e.attrs.role==='alert'||/error|Error|empty|Empty|fail|Fail/.test(e.attrs.class||'')||/error|Error/.test(e.attrs.id||'')||(selector.includes('.special-case-container')&&(e.attrs.class||'').split(/\s+/).includes('special-case-container')));throw new Error('unexpected selector '+selector);}};
 Object.defineProperty(document,'cookie',{get(){cookieReads++;throw new Error('must not read cookie');}});
 const window={innerHeight:720,innerWidth:400,location:{href:'https://www.douyin.com/video/'+ID},getComputedStyle(e){return e.style;}};
 window._ROUTER_DATA={loaderData:{target:{aweme_id:ID,video:{play_addr:{url_list:['https://synthetic.douyinvod.com/video.mp4']}}}}};
 const context=vm.createContext({window,document,fetch(){fetches++;throw new Error('must not fetch');}});
 return {window,document,nodes,primary,capture(){const raw=compiled.runInContext(context,{timeout:1000});assert.equal(typeof raw,'string');const value=JSON.parse(raw);assert.equal(cookieReads,0);assert.equal(fetches,0);return value;}};
}
const results=[];
function test(name,body){try{body(setup());results.push({name,ok:true});}catch(e){results.push({name,ok:false,error:e.message});}}
// Reconstructed from the user's screenshot labels, not a captured Douyin HTML document.
function playbackError(){const panel=new Element({class:'video-error'});panel.children=[new Element({},'抱歉出错了'),new Element({},'请尝试在抖音内观看'),new Element({},'打开抖音')];for(const child of panel.children)child.parentElement=panel;return panel;}
// Structure reconstructed from the isolated Chrome observation of the official public share page
// on 2026-10-03: .special-case-container > title/subtitle div > visible text span. No page HTML,
// cookies or private state are embedded here; Node stubs are still not a real browser test.
function observedSpecialCaseError(){
 const panel=new Element({class:'special-case-container'}),wrap=new Element({class:'special-case-wrap undefined'});
 panel.parentElement=wrap;wrap.children=[panel];
 const title=new Element({class:'special-case-title'}),subtitle=new Element({class:'special-case-subtitle'});
 title.children=[new Element({class:'special-font-title'},'抱歉出错了')];
 subtitle.children=[new Element({class:'special-font-subtitle'},'请尝试在抖音内观看')];
 panel.children=[title,subtitle];
 for(const branch of panel.children){branch.parentElement=panel;branch.children[0].parentElement=branch;branch.children[0].tagName='SPAN';}
 let parent=wrap;
 for(const [tag,attrs] of [['DIV',{class:'container'}],['DIV',{id:'root'}],['BODY',{}],['HTML',{}]]){
   const ancestor=new Element(attrs);ancestor.tagName=tag;ancestor.children=[parent];parent.parentElement=ancestor;parent=ancestor;
 }
 return panel;
}
test('schema snapshot and current document readiness',e=>{const r=e.capture();assert.equal(r.version,1);assert.equal(r.readyState,'complete');assert.equal(r.captureFailed,undefined);assert.equal(r.pageUrl,e.window.location.href);assert.equal(r.html,e.document.documentElement.outerHTML);});
test('live video dimensions duration and progressive src',e=>{const r=e.capture().videos[0];assert.equal(r.currentSrc,e.primary.currentSrc);assert.equal(r.width,720);assert.equal(r.height,1280);assert.equal(r.duration,7.5);assert.equal(r.readyState,3);assert.equal(r.mimeType,'video/mp4');});
test('visible target identity and canonical',e=>{const r=e.capture();assert.equal(r.videos[0].visible,true);assert.equal(r.videos[0].targetId,ID);assert.equal(r.videos[0].identityConflict,false);assert.equal(r.canonical,e.nodes.canonicals[0].href);});
test('known state emitted as JSON string',e=>{const r=e.capture();assert.equal(r.states.length,1);assert.equal(r.states[0].name,'_ROUTER_DATA');assert.equal(JSON.parse(r.states[0].json).loaderData.target.aweme_id,ID);});
test('global accessor not executed',e=>{Object.defineProperty(e.window,'SIGI_STATE',{get(){throw new Error('getter');}});assert.equal(e.capture().states.length,1);});
test('state own getters and toJSON functions are not invoked',e=>{let hits=0;e.window.__INITIAL_STATE__={plain:'yes',toJSON(){hits++;throw new Error('toJSON');}};Object.defineProperty(e.window.__INITIAL_STATE__,'secret',{enumerable:true,get(){hits++;throw new Error('getter');}});const r=e.capture();assert.equal(hits,0);assert.equal(JSON.parse(r.states.find(s=>s.name==='__INITIAL_STATE__').json).plain,'yes');});
test('cyclic state omitted while earlier good state remains',e=>{const c={};c.loop=c;e.window.__INITIAL_STATE__=c;assert.equal(e.capture().states.length,1);});
test('over depth state omitted',e=>{const deep={};let tail=deep;for(let i=0;i<34;i++){tail.next={};tail=tail.next;}e.window.__INITIAL_STATE__=deep;assert.equal(e.capture().states.length,1);});
test('oversized state string omitted',e=>{e.window.__INITIAL_STATE__='x'.repeat(524289);assert.equal(e.capture().states.length,1);});
test('state aggregate budget bounded',e=>{e.window.__INITIAL_STATE__='a'.repeat(300000);e.window.__PRELOADED_STATE__='b'.repeat(300000);const r=e.capture();assert.ok(r.states.reduce((n,s)=>n+s.json.length,0)<=524288);assert.equal(r.states.some(s=>s.name==='__PRELOADED_STATE__'),false);});
test('hidden challenge ignored',e=>{const d=new Element({},'请完成验证');d.style.display='none';e.nodes.verification=[d];assert.equal(e.capture().blocker.verification,false);});
test('visible challenge and login recognized',e=>{e.nodes.verification=[new Element({},'请完成验证')];e.nodes.login=[new Element({},'请先登录')];const r=e.capture();assert.equal(r.blocker.verification,true);assert.equal(r.blocker.login,true);});
test('generic login label not treated as blocking login',e=>{e.nodes.login=[new Element({},'登录')];assert.equal(e.capture().blocker.login,false);});
test('paired visible error labels from screenshot are recognized',e=>{e.nodes.playbackErrors=[playbackError()];const r=e.capture();assert.equal(r.blocker.webPlaybackUnavailable,true);assert.equal(r.blocker.verification,false);assert.equal(r.blocker.login,false);});
test('observed official special-case DOM shape is recognized without error class guesses',e=>{e.nodes.playbackErrors=[observedSpecialCaseError()];const r=e.capture();assert.equal(r.blocker.webPlaybackUnavailable,true);assert.equal(r.captureFailed,undefined);});
test('official special-case DOM still requires visible subtitle branch',e=>{const p=observedSpecialCaseError();p.children[1].style.display='none';e.nodes.playbackErrors=[p];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('official container class alone is not a playback failure',e=>{const p=observedSpecialCaseError();p.children[1].children[0].textContent='打开抖音';e.nodes.playbackErrors=[p];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('hidden error panel is ignored',e=>{const p=playbackError();p.style.display='none';e.nodes.playbackErrors=[p];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('hidden required label does not combine with visible error',e=>{const p=playbackError();p.children[1].style.visibility='hidden';e.nodes.playbackErrors=[p];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('transparent ancestor hides otherwise visible error panel',e=>{const p=playbackError();p.parentElement=new Element();p.parentElement.style.opacity='0';e.nodes.playbackErrors=[p];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('offscreen error is ignored',e=>{const p=playbackError();p.rect.top=900;e.nodes.playbackErrors=[p];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('generic open app banner is not a playback error',e=>{e.nodes.playbackErrors=[new Element({role:'alert'},'打开抖音')];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('author caption mentioning both phrases is not a playback error',e=>{const p=playbackError();p.attrs.class='video-caption';e.nodes.playbackErrors=[p];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('nested error labels inside author caption remain content',e=>{const p=playbackError();p.parentElement=new Element({'data-e2e':'video-desc'});e.nodes.playbackErrors=[p];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('quoted phrase pair inside one text node is not an error label pair',e=>{e.nodes.playbackErrors=[new Element({class:'error-caption'},'抱歉出错了 请尝试在抖音内观看')];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('labels from separate panels are not combined',e=>{e.nodes.playbackErrors=[new Element({class:'error'},'抱歉出错了'),new Element({class:'error'},'请尝试在抖音内观看')];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('page-wide error text cannot replace compact error panel',e=>{const p=playbackError();p.tagName='BODY';e.nodes.playbackErrors=[p];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('large subtree is rejected despite matching early labels',e=>{const p=playbackError();for(let i=0;i<40;i++)p.children.push(new Element());e.nodes.playbackErrors=[p];assert.equal(e.capture().blocker.webPlaybackUnavailable,false);});
test('error flag does not suppress available media observations',e=>{e.nodes.playbackErrors=[playbackError()];const r=e.capture();assert.equal(r.blocker.webPlaybackUnavailable,true);assert.equal(r.videos[0].currentSrc,e.primary.currentSrc);assert.equal(r.states.length,1);});
test('offscreen video not marked visible',e=>{e.primary.rect.top=900;assert.equal(e.capture().videos[0].visible,false);});
test('conflicting ancestor identity explicit',e=>{e.primary.parentElement=new Element({'data-item-id':'999'});const r=e.capture().videos[0];assert.equal(r.identityConflict,true);assert.equal(r.targetId,'');});
test('multiple canonicals explicit conflict',e=>{e.nodes.canonicals.push({href:'https://www.douyin.com/video/999'});const r=e.capture();assert.equal(r.canonicalConflict,true);assert.equal(r.canonical,'');});
test('encrypted source and unknown duration remain explicit',e=>{e.primary.mediaKeys={};e.primary.duration=NaN;const r=e.capture().videos[0];assert.equal(r.encrypted,true);assert.equal(r.duration,null);});
test('video node output bounded but full count retained',e=>{e.nodes.videos=Array(40).fill(e.primary);const r=e.capture();assert.equal(r.videos.length,32);assert.equal(r.videoCount,40);});
test('oversized HTML omitted instead of truncation',e=>{e.document.documentElement.outerHTML='x'.repeat(1048577);const r=e.capture();assert.equal(r.html,'');assert.equal(r.htmlOmitted,true);});
test('title bounded',e=>{e.document.title='x'.repeat(10000);assert.equal(e.capture().title.length,4096);});
test('unexpected DOM error becomes captureFailed',e=>{e.document.querySelectorAll=()=>{throw new Error('DOM failure');};const r=e.capture();assert.equal(r.captureFailed,true);assert.equal(r.pageUrl,e.window.location.href);});
const report={method:'Node V8 vm executing actual fixed capture JS extracted from Kotlin with deterministic synthetic DOM stubs; this is not browser or Android WebView runtime QA',observedAt:new Date().toISOString(),sourceSha256:createHash('sha256').update(source).digest('hex'),scriptSha256:createHash('sha256').update(script).digest('hex'),passed:results.filter(r=>r.ok).length,total:results.length,results};
if (process.argv[2]) await fs.writeFile(process.argv[2], JSON.stringify(report,null,2));
console.log(JSON.stringify(report,null,2));if(report.passed!==report.total)process.exitCode=1;

