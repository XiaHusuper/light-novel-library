window.ReaderAbout=function(options){
 'use strict';const $=id=>document.getElementById(id);let info=null,notices=null;
 async function openPage(page){try{await options.native('openProjectPage',{page});}catch(error){options.toast(error.message);}}
 $('aboutClose').onclick=()=>$('aboutScreen').classList.add('hidden');$('licenseClose').onclick=()=>$('licensesScreen').classList.add('hidden');
 $('aboutCheckUpdates').onclick=()=>openPage('latest');$('aboutChangelog').onclick=()=>openPage('changelog');$('aboutRepository').onclick=()=>openPage('repository');
 $('aboutLicenses').onclick=async()=>{$('licensesScreen').classList.remove('hidden');if(notices)return;const list=$('licenseList');list.textContent='正在读取本机许可声明…';try{const result=await options.native('aboutNotices',{}),fragment=document.createDocumentFragment();(result.items||[]).forEach(item=>{const section=document.createElement('details'),title=document.createElement('summary'),text=document.createElement('pre');title.textContent=item.title;text.textContent=item.text;section.append(title,text);fragment.appendChild(section);});list.textContent='';list.appendChild(fragment);notices=result;}catch(error){list.textContent=error.message;}};
 return {async open(){$('aboutScreen').classList.remove('hidden');try{if(!info)info=await options.native('aboutInfo',{});$('aboutVersion').textContent='版本 '+info.version+' · '+info.versionCode;$('aboutRepoName').textContent=info.repository;}catch(error){$('aboutVersion').textContent='版本信息暂时未能读取';options.toast(error.message);}}};
};
