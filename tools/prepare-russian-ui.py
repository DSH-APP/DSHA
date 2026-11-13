#!/usr/bin/env python3
"""生成俄语显示补丁；技术标识与用户内容不进入翻译边界。"""
import json,argparse
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];ASSETS=ROOT/'app/src/main/assets'

def generate():
 descriptions=json.loads((ROOT/'tools/i18n/builtin-ru.json').read_text())
 helper='''function dshaBuiltinRu(name, value) {
 const key=String(name).replace(/\\/client$/, "");
 const copy='''+json.dumps(descriptions,ensure_ascii=False)+''';
 if (!Object.prototype.hasOwnProperty.call(copy,key)) return value;
 if (value == null) {
  const fallback={"dsh-device-shell-guide":"Guides device shell commands through the authorized DSHA bridge.","dsh-status-overlay":"Displays assistant replies and tool status in the floating panel.","dsh-task-notifier":"Shows task progress and supports cancellation through the authorized app bridge."};
  return {en:fallback[key] || key,ru:copy[key]};
 }
 return typeof value === "string" ? {en:value,ru:copy[key]} : {...value,ru:copy[key]};
}
function dshaBuiltinDescription(name,value,resolveText) {
 const text=dshaBuiltinRu(name,value);
 return text == null ? undefined : resolveText(text) || undefined;
}
function dshaBuiltinTitle(name,value) {
 if (name !== "dsh-web-mobile" && name !== "dsh-web-mobile/client") return value;
 if (value === "Mobile adaptation") return {en:value,ru:"Мобильная адаптация"};
 return value && value.en === "Mobile adaptation" ? {...value,ru:"Мобильная адаптация"} : value;
}
'''
 plugin=[('function packageText(pkg, resolveText) {',helper+'\nfunction packageText(pkg, resolveText) {'),
 ('pkg.meta?.description === void 0 ? void 0 : resolveText(pkg.meta.description) || void 0','dshaBuiltinDescription(pkg.name, pkg.meta?.description, resolveText)'),
 ('row.meta?.description === void 0 ? void 0 : resolveText(row.meta.description) || void 0','dshaBuiltinDescription(row.moduleName, row.meta?.description, resolveText)'),
 ('resolveText(pkg.meta.title)','resolveText(dshaBuiltinTitle(pkg.name, pkg.meta.title))'),
 ('resolveText(row.meta.title)','resolveText(dshaBuiltinTitle(row.moduleName, row.meta.title))'),
 ('rowPhaseActive: "Бег",','rowPhaseActive: "Работает",'),('terminalRunning: "Бег",','terminalRunning: "Выполняется",')]
 welcome={
 'welcomeWaiting':'Сохранение ещё не завершено. Можно подождать, вернуться к уведомлению или войти только на этот раз. Выход из ожидания не отменяет запрос: он может завершиться позже.',
 'welcomeWaitLeft':'Вы вышли из ожидания. Запрос сохранения не отменён и может завершиться позже. Можно повторить сохранение или войти только на этот раз.',
 'welcomeSessionHint':'При входе только на этот раз подтверждение не сохраняется. После обновления страницы уведомление появится снова, если сохранение не успеет завершиться.',
 'welcomeDiagnostics':'Диагностика сохранения','welcomeReturn':'Вернуться к уведомлению','welcomeSessionOnly':'Я прочитал(а). Войти на этот раз без сохранения','welcomeSaving':'Сохранение…','welcomeRetry':'Повторить сохранение'}
 anchor='welcomeContinue: "Продолжить",'
 css='.zGbnIq_addModes{flex-direction:column;align-items:flex-start;gap:8px;display:flex}'
 fix='.zGbnIq_section,.zGbnIq_addBlock,.zGbnIq_addCard,.zGbnIq_addModes{min-width:0;max-width:100%;box-sizing:border-box}.zGbnIq_addModes>[role=tablist]{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));width:100%;min-width:0;max-width:100%;box-sizing:border-box}.zGbnIq_addModes [role=tab]{min-width:0;max-width:100%;white-space:normal;overflow-wrap:anywhere;line-height:1.4;height:auto;min-height:44px;box-sizing:border-box}'
 models=[(anchor,anchor+'\n'+''.join(k+': '+json.dumps(v,ensure_ascii=False)+',\n' for k,v in welcome.items())),(css,css+fix)]
 result=[('russian-plugin-ui-patch.json','@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js',plugin),('russian-model-ui-patch.json','@deepseek-ai/dsh-client-ui-settings-models/lib/client.js',models)]
 for recipe in json.loads((ROOT/'tools/i18n/russian-followup-patches.json').read_text()):
  result.append((recipe['asset'],recipe['module'],[(p['before'],p['after']) for p in recipe['patches']]))
 return result

def main():
 p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');a=p.parse_args()
 for name,module,patches in generate():
  recipe=dict(version=1,dshVersion='0.2.2-rc.1',module=module,patches=[dict(before=b,after=v) for b,v in patches])
  if name=='russian-plugin-ui-patch.json':
   recipe['previousPatches']=json.loads((ROOT/'tools/fixtures/russian-ui-v1-plugin-patch.json').read_text())['patches']
  text=json.dumps(recipe,ensure_ascii=False,indent=2)+'\n';file=ASSETS/name
  if a.check:
   if file.read_text()!=text:raise SystemExit('RUSSIAN_UI_STALE: '+name)
  else:file.write_text(text)
 print('PASS Russian UI recipes')
if __name__=='__main__':main()
