'use strict';
const $ = id => document.getElementById(id);
const state = {files: [], selected: null, health: null, cases: [], detailKey: '', uploading: false, cloudCase: null};
const statusText = {queued:'等待中',running:'辨識中',succeeded:'已產生候選',failed:'辨識失敗',cancelled:'已取消',interrupted:'已中斷'};
function el(tag, text, cls) {const n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n;}
function button(text, fn, cls='secondary') {const b=el('button',text,cls);b.onclick=()=>Promise.resolve(fn()).catch(error=>message(error.message));return b;}
function message(text) {$('message').textContent=text;$('message').style.display='block';clearTimeout(message.timer);message.timer=setTimeout(()=>$('message').style.display='none',6500);}
async function api(path, method='GET', value=null, bytes=null) {
  const headers={'X-Receipt-Lab':'1'};
  if(value!==null)headers['Content-Type']='application/json';
  const response=await fetch(path,{method,headers,body:bytes ?? (value===null?undefined:JSON.stringify(value))});
  const result=await response.json(); if(!response.ok)throw Error(result.error||'服務未完成請求'); return result;
}
function selectFiles(files) {
  state.files=Array.from(files); $('selection').textContent=state.files.length?`已選 ${state.files.length} 張 · ${Math.round(state.files.reduce((n,f)=>n+f.size,0)/1024)} KiB`:'可一次加入多張收據';
  uploadButtons();
}
function uploadButtons(){ $('upload').disabled=state.uploading||!state.files.length||!state.health?.engines.mlkit.configured; $('save-only').disabled=state.uploading||!state.files.length;}
$('files').onchange=e=>selectFiles(e.target.files);
$('drop').onkeydown=e=>{if(e.target===$('drop')&&(e.key==='Enter'||e.key===' ')){e.preventDefault();$('files').click();}};
for(const name of ['dragenter','dragover'])$('drop').addEventListener(name,e=>{e.preventDefault();$('drop').classList.add('drag');});
for(const name of ['dragleave','drop'])$('drop').addEventListener(name,e=>{e.preventDefault();$('drop').classList.remove('drag');});
$('drop').addEventListener('drop',e=>selectFiles(e.dataTransfer.files));
async function upload(run){
  if(!state.files.length)return;
  if(state.files.length>20)throw Error('一次最多選 20 張；可以分批加入。');
  if(state.files.some(f=>f.size>20*1024*1024))throw Error('有照片超過 20 MiB。');
  state.uploading=true;uploadButtons();
  let done=0;
  try{
    const groups=$('same').checked?[state.files]:state.files.map(f=>[f]);
    for(const group of groups){
      const hashes=[];
      for(const file of group){$('upload-progress').textContent=`正在保存照片：${++done} / ${state.files.length}`;
        const image=await api('/api/images?name='+encodeURIComponent(file.name),'POST',null,file);if(!hashes.includes(image.id))hashes.push(image.id);}
      const item=await api('/api/cases','POST',{title:group[0].name.replace(/\.[^.]+$/,''),images:hashes});
      state.selected=item.id;
      if(run)await runCase(item,'mlkit');
    }
    $('upload-progress').textContent=run?'照片已保存，辨識結果會自動更新。':'照片已保存，可稍後由 API 或這裡執行測試。';
    selectFiles([]);$('files').value='';await refresh();
  }catch(error){$('upload-progress').textContent='本批未全部完成；已建立的案例保留在下方。'+error.message;await refresh();throw error;}
  finally{state.uploading=false;uploadButtons();}
}
$('upload').onclick=()=>upload(true).catch(e=>message(e.message));
$('save-only').onclick=()=>upload(false).catch(e=>message(e.message));
async function runCase(item,engine,consent=null){
  const key=`receipt-run-${item.id}-${engine}`;
  const requestId=sessionStorage.getItem(key)||crypto.randomUUID();sessionStorage.setItem(key,requestId);
  const body={engine,requestId};if(consent)body.consent=consent;
  await api(`/api/cases/${item.id}/runs`,'POST',body);sessionStorage.removeItem(key);
  state.detailKey='';message('已加入測試佇列，結果將自動保存。');
}
function renderCases(){
  $('count').textContent=state.cases.length;$('cases').replaceChildren();
  if(!state.cases.length){$('cases').append(el('p','還沒有收據。先從上方加入照片。','meta'));return;}
  for(const item of state.cases){
    const b=button('',async()=>{state.selected=item.id;state.detailKey='';await refresh();},'case'+(item.id===state.selected?' active':''));
    b.append(el('span',item.title,'case-title'),el('small',`${item.images.length} 張照片 · ${item.runs.length} 次測試`));
    if(item.runs[0])b.append(el('span',statusText[item.runs[0].status],`badge ${item.runs[0].status}`));
    b.setAttribute('aria-label',`查看 ${item.title}`);$('cases').append(b);
  }
}
function rawSection(label, data){const d=el('details');d.append(el('summary',label),el('pre',typeof data==='string'?data:JSON.stringify(data,null,2)));return d;}
function valueCell(value){return el('td',value??'未知',value==null?'unknown':null);}
function renderRun(run){
  const box=el('section',null,'run');const heading=el('div',null,'section-row');
  const engineNames={'mlkit':'傳統 OCR＋解析器','nano-image':'Gemini Nano · 原圖','nano-ocr':'Gemini Nano · 原圖＋OCR'};
  heading.append(el('h3',engineNames[run.engine]??`Gemini 雲端 · ${run.request.model}`),el('span',statusText[run.status],`badge ${run.status}`));box.append(heading);
  box.append(el('p',new Date(run.createdAt).toLocaleString('zh-TW')+' · '+(run.elapsedMs!=null?`${(run.elapsedMs/1000).toFixed(1)} 秒`:'等待執行結果'),'meta'));
  if(['queued','running'].includes(run.status))box.append(button('取消這次測試',async()=>{await api(`/api/runs/${run.id}/cancel`,'POST',{});await refresh();}));
  if(run.error){box.append(el('p',run.error.split('\n')[0].slice(0,250),'error'));if(run.error.includes('\n')||run.error.length>250)box.append(rawSection('展開技術診斷',run.error));}
  const result=run.result;
  if(result){
    if(run.diagnostics){const m=el('div',null,'metrics');for(const [label,v] of [['擷取品項',run.diagnostics.itemCount],['核心未知欄位',run.diagnostics.unknownFields],['正確率','尚未評估']]){const item=el('div');item.append(el('strong',v??'—'),el('span',label));m.append(item);}box.append(m);}
    if(result.candidate){
      const c=result.candidate;box.append(el('p',`${c.merchant??'商家未知'} · ${c.date??'日期未知'} · 收據總額 ${c.totalMinor??'未知'} ${c.currency??''}`,'meta'));
      const showUnit=run.engine!=='mlkit';
      const table=el('table');const head=el('tr');for(const t of (showUnit?['品名','數量','單價','行合計']:['品名','數量','行合計']))head.append(el('th',t));table.append(head);
      for(const row of c.items){const tr=el('tr');tr.append(valueCell(row.name),valueCell(row.quantity));if(showUnit)tr.append(valueCell(row.unitPriceMinor));tr.append(valueCell(row.lineTotalMinor));table.append(tr);}
      const wrap=el('div',null,'table-wrap');wrap.append(table);box.append(wrap);
      if(c.adjustments.length)box.append(rawSection(`另列折扣／費用 ${c.adjustments.length} 筆（範圍仍須核對）`,c.adjustments));
      if(run.engine==='mlkit')box.append(el('p','表格是 App 實際映射的候選；單價觀察保留於下方原始解析結果，不會重乘數量。','meta'));
    }
    if(result.warnings?.length)box.append(rawSection(`待核對與警告 · ${result.warnings.length}`,result.warnings.join('\n')));
    if(result.ocrPages)box.append(rawSection('原始 OCR 與重建欄位文字',result.ocrPages.map((p,i)=>`【第 ${i+1} 頁】\n`+p.lines.map(l=>l.rawText===l.text||!l.rawText?l.text:`${l.rawText}\n分欄：${l.text}`).join('\n')).join('\n\n')));
    box.append(rawSection('完整結果、來源與版本',result));
  }
  return box;
}
function renderDetail(item){
  const root=$('detail');root.replaceChildren();
  const heading=el('div',null,'section-row');heading.append(el('h2',item.title),el('span',item.dataset==='acceptance'?'驗收樣本':'開發樣本','badge'));root.append(heading);
  const photos=el('div',null,'photos');item.imageDetails.forEach((image,i)=>{const link=el('a',null);link.href=`/api/images/${image.id}`;link.target='_blank';link.rel='noopener';const img=el('img');img.src=link.href;img.alt=`收據第 ${i+1} 張：${image.name}`;link.append(img,el('span',`照片 ${i+1} · 點開原圖`));photos.append(link);});root.append(photos);
  const actions=el('div',null,'actions');const local=button('重跑本機辨識',async()=>{await runCase(item,'mlkit');await refresh();},'');local.disabled=!state.health.engines.mlkit.configured;
  const cloud=button(state.health.engines.gemini.configured?'用 Gemini 比較':'Gemini 尚未設定',()=>{state.cloudCase=item;$('cloud-info').textContent=`${item.title} · ${item.images.length} 張照片 · 模型 ${state.health.engines.gemini.model}`;$('cloud-dialog').showModal();});cloud.disabled=!state.health.engines.gemini.configured;
  actions.append(local,cloud,button('匯出完整結果',()=>{const url=URL.createObjectURL(new Blob([JSON.stringify(item,null,2)],{type:'application/json'}));const a=el('a');a.href=url;a.download=`receipt-${item.id}.json`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}));root.append(actions);
  for(const [engine,label] of [['nano-image','Nano 讀原圖'],['nano-ocr','Nano 讀原圖＋OCR']]){
    const b=button(label,async()=>{await runCase(item,engine);await refresh();});
    b.disabled=!state.health.engines[engine]?.configured||item.images.length!==1;actions.append(b);
  }
  root.append(el('p','候選清單不會改動手機帳本。不同引擎的品項數或未知數可比較，但不能當作準確率。','meta'));
  if(item.notes.length){root.append(el('h3','測試分析'));for(const note of item.notes){const n=el('div',note.text,'note');n.append(el('time',new Date(note.createdAt).toLocaleString('zh-TW')));root.append(n);}}
  if(!item.runs.length)root.append(el('p','照片已就緒，尚未執行辨識。你可以離開，稍後再回來看結果。','empty'));
  for(const run of item.runs)root.append(renderRun(run));
  const remove=button('刪除此測試收據',async()=>{if(!confirm('刪除此筆測試收據、所有測試結果與未共用照片？手機帳本不受影響。'))return;await api(`/api/cases/${item.id}`,'DELETE');state.selected=null;state.detailKey='';await refresh();},'text danger');
  remove.disabled=item.runs.some(r=>['queued','running'].includes(r.status));root.append(remove);
}
async function refresh(){
  const data=await api('/api/cases');state.cases=data.cases;
  if(!state.cases.some(c=>c.id===state.selected))state.selected=state.cases[0]?.id??null;
  renderCases();
  if(state.selected){const item=await api(`/api/cases/${state.selected}`);const key=JSON.stringify(item);if(key!==state.detailKey){renderDetail(item);state.detailKey=key;}}
  else if(state.detailKey){$('detail').replaceChildren(el('p','尚無收據。從上方加入照片即可開始。','empty'));state.detailKey='';}
}
$('refresh').onclick=()=>refresh().catch(e=>message(e.message));
$('cloud-cancel').onclick=()=>$('cloud-dialog').close();
$('cloud-confirm').onclick=async()=>{const item=state.cloudCase;$('cloud-dialog').close();try{await runCase(item,'gemini',{provider:'google-gemini',model:state.health.engines.gemini.model,purpose:'receipt-extraction',imageHashes:item.images});await refresh();}catch(e){message(e.message);}};
async function init(){state.health=await api('/api/health');const engines=state.health.engines;$('engines').textContent=`本機 OCR ${engines.mlkit.configured?'已設定':'未設定'} · Gemini 雲端 ${engines.gemini.configured?'已設定':'未設定'} · Nano ${engines['nano-image']?.configured?'已指定 Pixel，能力待實測':'尚未指定 Pixel'}`;uploadButtons();await refresh();}
init().then(()=>setInterval(()=>refresh().catch(()=>{}),2000)).catch(e=>message(e.message));
