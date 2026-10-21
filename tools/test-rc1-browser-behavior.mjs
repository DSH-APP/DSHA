import assert from 'node:assert/strict';
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';
import {testRuntime} from './test-runtime-fixture.mjs';
const fixture=await browserFixture(testRuntime('raw'));
try {
  await fixture.load('dsh-client-ui-conversation', ['lexical-claim-patch.json','conversation-materialized-patch.json','composer-enter-patch.json','queue-dock-patch.json'].map(name=>JSON.parse(fs.readFileSync('app/src/main/assets/'+name,'utf8'))),
    'exports.audit={DraftEditorRuntime,CommandChipNode,root:nl,undo:Qe$2,redo:Ze$2,ConversationNodeAssembler};');
  const lexical=await fixture.page.evaluate(async()=>{
    const a=auditExports['@deepseek-ai/dsh-client-ui-conversation'].audit;
    const lexicon=new Map();let runtime;
    runtime=new a.DraftEditorRuntime({onUpdate:()=>runtime.refreshProjection(),openReference:()=>false,
      lexicon:()=>lexicon,resolveLexicon:()=>undefined});
    const off=runtime.register();
    const root=document.createElement('div');root.contentEditable='true';root.id='command-fixture';
    document.body.append(root);runtime.editor.setRootElement(root);root.focus();
    const snapshot=()=>{runtime.refreshProjection();return runtime.editor.getEditorState().read(()=>({
      text:runtime.projection.clipboardText,command:runtime.projection.command?.name??null,
      nodes:a.root().getFirstChild()?.getChildren().map(n=>({type:n.getType(),text:n.getTextContent(),style:n.getStyle?.()??''}))??[],
      chip:root.querySelector('[data-composer-command]')?.getAttribute('contenteditable')??null
    }));};
    runtime.setDraft('/test');runtime.clearHistory();
    const applied=runtime.insertCommand({start:0,end:5},{name:'fixture',token:'/test ',submit:()=>{throw Error('command must not submit');}},'');
    const claimed=snapshot();runtime.paste('内容');const pasted=snapshot();
    runtime.editor.dispatchCommand(a.undo,undefined);await new Promise(r=>setTimeout(r,0));const undone=snapshot();
    runtime.editor.dispatchCommand(a.redo,undefined);await new Promise(r=>setTimeout(r,0));const redone=snapshot();
    window.commandFixture={runtime,root,snapshot,off};
    return {applied,claimed,pasted,undone,redone};
  });
  assert.equal(lexical.applied,true);
  assert.equal(lexical.claimed.command,'fixture');assert.equal(lexical.claimed.chip,'false');
  assert.equal(lexical.claimed.nodes[0].type,'command-chip');assert.equal(lexical.claimed.nodes[0].text,'/test');
  assert.equal(lexical.pasted.text,'/test 内容');assert.equal(lexical.pasted.command,'fixture');
  assert.equal(lexical.undone.text,'/test ');assert.equal(lexical.undone.command,'fixture');
  assert.deepEqual(lexical.redone,lexical.pasted);
  // Browser text input is observed through the mounted editor's real handlers.
  // Android IME composition remains a separate device acceptance item.
  await fixture.page.locator('#command-fixture').focus();
  await fixture.page.keyboard.insertText('输入法');
  lexical.typed=await fixture.page.evaluate(()=>commandFixture.snapshot());
  assert.equal(lexical.typed.text,'/test 内容输入法');assert.equal(lexical.typed.command,'fixture');
  assert.ok(lexical.typed.nodes.filter(n=>n.type==='text').every(n=>n.style===''));
  lexical.restored=await fixture.page.evaluate(()=>{
    const {runtime,root,snapshot,off}=commandFixture;
    const serialized=runtime.editor.getEditorState().toJSON();
    runtime.editor.setEditorState(runtime.editor.parseEditorState(JSON.stringify(serialized)));
    const result=snapshot();off();root.remove();delete window.commandFixture;return result;
  });
  assert.equal(lexical.restored.text,lexical.typed.text);assert.equal(lexical.restored.command,null);
  assert.ok(lexical.restored.nodes.every(n=>n.type!=='command-chip'));
  const materialized=await fixture.page.evaluate(()=>{
    const {ConversationNodeAssembler}=auditExports['@deepseek-ai/dsh-client-ui-conversation'].audit;
    const assembler=Object.create(ConversationNodeAssembler.prototype);
    const context={current:new Map()};let next={key:'stable',target:'messages',visibility:'visible',text:'retained'};
    assembler.buildNode=()=>next;
    const visible=assembler.buildTargetUpserts('messages',[context])[0];next=null;
    const hidden=assembler.buildTargetUpserts('messages',[context])[0];
    const repeated=assembler.buildTargetUpserts('messages',[context])[0];
    next={...visible,text:'new'};const restored=assembler.buildTargetUpserts('messages',[context])[0];
    const reset=assembler.buildTargetNodes('messages',[]);
    return {visible,hidden,repeatedSame:repeated===hidden,restored,reset};
  });
  assert.equal(materialized.hidden.key,materialized.visible.key);assert.equal(materialized.hidden.visibility,'hidden');
  assert.equal(materialized.hidden.text,'retained');assert.equal(materialized.repeatedSame,true);
  assert.equal(materialized.restored.visibility,'visible');assert.equal(materialized.restored.text,'new');assert.deepEqual(materialized.reset,[]);
  assert.deepEqual(fixture.errors,[]);
  console.log(JSON.stringify({status:'PASS',engine:await fixture.browser.version(),lexical,materialized},null,2));
}finally{await fixture.close();}
