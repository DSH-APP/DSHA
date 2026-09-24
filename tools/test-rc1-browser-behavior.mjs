import assert from 'node:assert/strict';
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';
const fixture=await browserFixture();
try {
  await fixture.load('dsh-client-ui-conversation', ['lexical-claim-patch.json','conversation-materialized-patch.json'].map(name=>JSON.parse(fs.readFileSync('app/src/main/assets/'+name,'utf8'))),
    'exports.audit={createEditor:ys,root:nl,text:Go,paragraph:es,registerClaimDecoration,refreshClaimDecoration,TOKEN_STYLE,ConversationNodeAssembler};');
  const lexical=await fixture.page.evaluate(async()=>{
    const a=auditExports['@deepseek-ai/dsh-client-ui-conversation'].audit;
    const errors=[];const editor=a.createEditor({namespace:'fixture',onError:e=>errors.push(String(e))});
    const root=document.createElement('div');root.contentEditable='true';document.body.append(root);editor.setRootElement(root);
    let token='/test ';const off=a.registerClaimDecoration(editor,()=>token);
    editor.update(()=>a.root().append(a.paragraph().append(a.text('/test '))),{discrete:true});
    editor.update(()=>a.root().getFirstChild().getFirstChild().setTextContent('/test 内容'),{discrete:true});
    const snapshot=()=>editor.getEditorState().read(()=>a.root().getFirstChild().getChildren().map(n=>({text:n.getTextContent(),style:n.getStyle(),key:n.getKey()})));
    const split=snapshot();
    editor.update(()=>a.root().getFirstChild().getLastChild().setTextContent('内容输入法'),{discrete:true});
    const composed=snapshot(); token=null;a.refreshClaimDecoration(editor);await new Promise(r=>setTimeout(r,0));
    const unclaimed=snapshot();off();editor.setRootElement(null);root.remove();
    return {errors,split,composed,unclaimed};
  });
  assert.deepEqual(lexical.errors,[]);
  assert.equal(lexical.split.map(x=>x.text).join(''),'/test 内容');
  assert.equal(lexical.split.length,2);assert.notEqual(lexical.split[0].style,'');assert.equal(lexical.split[1].style,'');
  assert.equal(lexical.composed.map(x=>x.text).join(''),'/test 内容输入法');
  assert.equal(lexical.unclaimed.map(x=>x.text).join(''),'/test 内容输入法');
  assert.ok(lexical.unclaimed.every(x=>!x.style));
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
