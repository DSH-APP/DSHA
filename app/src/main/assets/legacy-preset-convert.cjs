// Parse data into a current loader patch; never evaluate legacy !!js expressions.
const fs=require('node:fs'), yaml=require(process.argv[3]);
try {
  const input=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
  if(!/^[a-z0-9][a-z0-9-]{0,63}$/.test(input.id)||typeof input.agent!=='string'||input.agent.length>512*1024)throw Error();
  const agent=yaml.parseDocument(input.agent,{uniqueKeys:true,customTags:[{tag:'tag:yaml.org,2002:js',resolve:value=>value}]});
  if(agent.errors.length||agent.warnings.length||!yaml.isSeq(agent.contents))throw Error();
  const patch=new yaml.Document([{insert:[{id:'preset-'+input.id,name:'@deepseek-ai/dsh-agent-preset',config:{id:input.id,name:input.id,plugins:agent.contents}}]}]);
  const output=patch.toString();
  const checked=yaml.parseDocument(output,{uniqueKeys:true,customTags:[{tag:'tag:yaml.org,2002:js',resolve:value=>value}]});
  if(checked.errors.length||checked.warnings.length)throw Error();
  process.stdout.write(JSON.stringify({patch:output}));
}catch{process.stderr.write('PRESET_CONFIGURATION_INVALID\n');process.exitCode=65;}
