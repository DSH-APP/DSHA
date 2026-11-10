// Static AST only. The request contains bytes read by the NOFOLLOW Python reader.
const acorn=require('./plugin-acorn.cjs');
const fs=require('node:fs');
const MAX_FINDINGS=200, MAX_NODES=100000;
function analyze(files) {
  const findings=[], unknown=[];
  const add=row=>{ if(findings.length<MAX_FINDINGS) findings.push(row); else if(!unknown.includes('finding-limit')) unknown.push('finding-limit'); };
  function literal(node) {
    if(node?.type==='Literal'&&typeof node.value==='string') return node.value;
    if(node?.type==='ArrayExpression') { const values=node.elements.map(literal); return values.every(v=>typeof v==='string')?values:undefined; }
    if(node?.type==='ObjectExpression') {
      const result={};
      for(const prop of node.properties) {
        if(prop.type!=='Property'||prop.kind!=='init'||prop.computed||prop.method) return undefined;
        const key=prop.key.name??prop.key.value, value=literal(prop.value);
        if(typeof key!=='string'||value===undefined||key==='__proto__') return undefined;
        result[key]=value;
      }
      return result;
    }
    return undefined;
  }
  const label=value=>String(value).replace(/[\r\n\t]/g,' ').replace(/(?:sk-|Bearer\s+)\S+/gi,'[redacted]').slice(0,120);
  function inject(node,file) {
    const value=literal(node);
    if(value===undefined) { unknown.push(file+':dynamic-inject');return; }
    const items=Array.isArray(value)?value:typeof value==='string'?[value]:Object.entries(value).flatMap(([key,values])=>Array.isArray(values)?values.map(v=>key+': '+v):[]);
    for(const service of items) add({kind:'server-inject',value:label(service),file,line:node.loc.start.line});
  }
  for(const item of files) {
    try {
      const ast=acorn.parse(item.source,{ecmaVersion:'latest',sourceType:'module',locations:true,allowHashBang:true});
      const declarations=new Map();
      for(const statement of ast.body) {
        const declaration=statement.type==='ExportNamedDeclaration'?statement.declaration:statement;
        if(declaration?.type==='VariableDeclaration') for(const d of declaration.declarations) if(d.id.type==='Identifier') declarations.set(d.id.name,d.init);
      }
      for(const statement of ast.body) if(statement.type==='ExportNamedDeclaration') {
        if(statement.declaration?.type==='VariableDeclaration') for(const d of statement.declaration.declarations) if(d.id.name==='inject') inject(d.init,item.file);
        for(const specifier of statement.specifiers??[]) if(specifier.exported.name==='inject') {
          if(statement.source) unknown.push(item.file+':reexported-inject'); else inject(declarations.get(specifier.local.name),item.file);
        }
      }
      const propertyName=node=>node?.computed?node.property?.type==='Literal'?node.property.value:undefined:node.property?.name;
      const exported=node=>node?.type==='Identifier'&&node.name==='exports'||node?.type==='MemberExpression'&&node.object?.name==='module'&&propertyName(node)==='exports';
      const objectInject=node=>{ if(node?.type==='ObjectExpression') for(const prop of node.properties)
        if(prop.type==='Property'&&prop.kind==='init'&&!prop.computed&&(prop.key.name??prop.key.value)==='inject') inject(prop.value,item.file); };
      for(const statement of ast.body) if(statement.type==='ExportDefaultDeclaration') objectInject(statement.declaration);
      const pending=[ast]; let nodes=0;
      while(pending.length) {
        const node=pending.pop(); if(++nodes>MAX_NODES) { unknown.push(item.file+':node-limit');break; }
        if(node.type==='AssignmentExpression'&&node.operator==='=') {
          if(node.left.type==='MemberExpression'&&exported(node.left.object)&&propertyName(node.left)==='inject') inject(node.right,item.file);
          else if(exported(node.left)) objectInject(node.right);
        }
        if(node.type==='CallExpression' && node.callee.type==='Identifier' && node.callee.name==='fetch') {
          const target=literal(node.arguments[0]);
          if(typeof target==='string') {
            try { const url=new URL(target),port=Number(url.port);
              if(['127.0.0.1','localhost','[::1]'].includes(url.hostname)&&Number.isInteger(port)&&port>=3090&&port<=3099) add({kind:'bridge-reference',value:label(url.pathname),file:item.file,line:node.loc.start.line});
            } catch { unknown.push(item.file+':dynamic-bridge'); }
          } else unknown.push(item.file+':dynamic-bridge');
        }
        for(const [key,value] of Object.entries(node)) if(key!=='loc') {
          if(Array.isArray(value)) { for(const child of value) if(child&&typeof child.type==='string') pending.push(child); }
          else if(value&&typeof value.type==='string') pending.push(value);
        }
      }
    } catch { unknown.push(item.file+':unparsed-source'); }
  }
  return {findings,unknown:[...new Set(unknown)].slice(0,200),parser:'acorn@8.15.0'};
}
module.exports={analyze};
if(require.main===module) {
  const input=fs.readFileSync(0); if(input.length>9*1024*1024) throw Error('INPUT_LIMIT');
  const request=JSON.parse(input); if(!Array.isArray(request.files)||request.files.length>256) throw Error('FILE_LIMIT');
  process.stdout.write(JSON.stringify(analyze(request.files)));
}
