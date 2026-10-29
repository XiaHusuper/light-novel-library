window.ReaderAbout=function(options){
 'use strict';const $=id=>document.getElementById(id);let info=null,notices=null,update={},timer,polling=false,lastAction='updateCheck',installing=false,autoTimer,autoRun=0,noticedVersion='';
 const busy=()=>['checking','downloading','verifying'].includes(update.state),size=bytes=>(Number(bytes||0)/1048576).toFixed(1)+' MB';
 async function openPage(page){try{await options.native('openProjectPage',{page});}catch(error){options.toast(error.message);}}
 function schedule(){clearTimeout(timer);if(!$('updateScreen').classList.contains('hidden')&&busy())timer=setTimeout(poll,500);}
 function render(value){
  update=value||{};const phase=update.state||'idle';if(!$('updateScreen').classList.contains('hidden')&&['available','ready','downloading','verifying'].includes(phase))noticedVersion=update.latestVersion||noticedVersion;
  $('updateCurrent').textContent='当前版本 '+(update.version||(info||{}).version||'');
  $('updateTitle').textContent=phase==='current'?'已是最新版本':phase==='ready'?'更新已准备好':update.latestVersion?'发现新版本 '+update.latestVersion:'检查更新';
  $('updateStatus').textContent=update.error||update.message||(phase==='checking'?'正在检查新版本…':'');
  $('updateNotes').textContent=update.notes||'';$('updateNotes').classList.toggle('hidden',!update.notes);
  const downloading=['downloading','verifying'].includes(phase);$('updateProgressBox').classList.toggle('hidden',!downloading);
  $('updateProgress').value=Math.max(0,Math.min(100,Number(update.progress)||0));$('updateBytes').textContent=size(update.bytes)+' / '+size(update.total)+(phase==='verifying'?' · 正在核对':'');
  $('updateAction').disabled=busy()||phase==='unsupported';$('updateCancel').classList.toggle('hidden',!downloading);
  $('updateAction').textContent=phase==='ready'?'安装更新':phase==='available'?'下载并更新 · '+size(update.total):phase==='error'?'重试':phase==='unsupported'?'当前系统暂不支持':busy()?'请稍候…':'重新检查';
  $('updateHint').textContent=phase==='ready'&&!update.installAllowed?'首次更新需允许本应用安装更新包，授权后会打开系统安装界面。':'下载完成后由安卓系统确认安装，书架与本地阅读记录会保留。';
  schedule();
 }
 async function action(operation){
  lastAction=operation;try{render(await options.native(operation,{}));}catch(error){update=Object.assign({},update,{state:'error',error:error.message});render(update);}
 }
 async function poll(){if(polling)return;polling=true;try{render(await options.native('updateStatus',{}));}catch(error){render(Object.assign({},update,{state:'error',error:error.message}));}finally{polling=false;}}
 function hideNotice(){ $('updateNoticeSheet').classList.add('hidden');$('updateNoticeBackdrop').classList.add('hidden');}
 function deferNotice(){hideNotice();options.native('updateDefer',{}).catch(()=>{});}
 async function automatic(run){
  try{let value=await options.native('updateAutoCheck',{});while(value.state==='checking'&&run===autoRun){await new Promise(resolve=>setTimeout(resolve,800));if(run!==autoRun)return;value=await options.native('updateStatus',{});}if(run!==autoRun)return;
   if(!['available','ready'].includes(value.state)||!value.noticeAllowed||noticedVersion===value.latestVersion)return;
   const offer=()=>{if(run!==autoRun||noticedVersion===value.latestVersion)return;if(options.canNotify&&!options.canNotify()){autoTimer=setTimeout(offer,4000);return;}noticedVersion=value.latestVersion;$('updateNoticeTitle').textContent='发现新版本 '+value.latestVersion;$('updateNoticeText').textContent='可以在应用内下载并安装更新。';$('updateNoticeSheet').classList.remove('hidden');$('updateNoticeBackdrop').classList.remove('hidden');$('updateNoticeNow').onclick=()=>{hideNotice();$('updateScreen').classList.remove('hidden');render(value);};};offer();
  }catch(ignored){}
 }
 $('updateNoticeLater').onclick=deferNotice;$('updateNoticeBackdrop').onclick=deferNotice;
 $('aboutClose').onclick=()=>$('aboutScreen').classList.add('hidden');$('licenseClose').onclick=()=>$('licensesScreen').classList.add('hidden');
 $('aboutCheckUpdates').onclick=()=>{clearTimeout(autoTimer);autoRun++;$('updateScreen').classList.remove('hidden');render({state:'checking',version:(info||{}).version,message:'正在检查新版本…'});action('updateCheck');};
 $('updateClose').onclick=()=>{$('updateScreen').classList.add('hidden');clearTimeout(timer);};
 $('updateCancel').onclick=()=>action('updateCancel');
 $('updateAction').onclick=async()=>{
  if(busy())return;
  if(update.state==='ready'){if(installing)return;installing=true;$('updateAction').disabled=true;try{const result=await options.native('updateInstall',{});if(result.permissionRequired)$('updateHint').textContent='请允许安装更新包，返回后会继续打开系统安装界面。';}catch(error){options.toast(error.message);await poll();}finally{installing=false;$('updateAction').disabled=busy();}return;}
  action(update.state==='available'?'updateDownload':update.state==='error'&&lastAction==='updateDownload'?'updateDownload':'updateCheck');
 };
 $('aboutChangelog').onclick=()=>openPage('changelog');$('aboutRepository').onclick=()=>openPage('repository');
 $('aboutLicenses').onclick=async()=>{$('licensesScreen').classList.remove('hidden');if(notices)return;const list=$('licenseList');list.textContent='正在读取本机许可声明…';try{const result=await options.native('aboutNotices',{}),fragment=document.createDocumentFragment();(result.items||[]).forEach(item=>{const section=document.createElement('details'),title=document.createElement('summary'),text=document.createElement('pre');title.textContent=item.title;text.textContent=item.text;section.append(title,text);fragment.appendChild(section);});list.textContent='';list.appendChild(fragment);notices=result;}catch(error){list.textContent=error.message;}};
 return {autoCheck(){clearTimeout(autoTimer);const run=++autoRun;autoTimer=setTimeout(()=>automatic(run),2500);},pauseAuto(){clearTimeout(autoTimer);autoRun++;},deferNotice,async open(){$('aboutScreen').classList.remove('hidden');try{if(!info)info=await options.native('aboutInfo',{});$('aboutVersion').textContent='版本 '+info.version+' · '+info.versionCode;$('aboutRepoName').textContent=info.repository;}catch(error){$('aboutVersion').textContent='版本信息暂时未能读取';options.toast(error.message);}}};
};
