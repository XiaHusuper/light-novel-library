/* Same-origin, one-page request in the retained official WebView. No native bridge or password. */
(function(root,token,address,page,parse){
 'use strict';
 const previous=root.__lnlOfficialXHR;if(previous&&previous.xhr&&previous.xhr.readyState!==4)previous.xhr.abort();
 const job={token,done:false,value:null,xhr:null};root.__lnlOfficialXHR=job;
 const finish=value=>{if(root.__lnlOfficialXHR===job){job.value=value;job.done=true;}};
 try{
  const target=new URL(address,location.href);
  if(location.origin!=='https://www.wenku8.net'||target.origin!==location.origin||target.pathname!=='/modules/article/toplist.php'||target.searchParams.get('sort')!=='lastupdate')throw Error('origin');
  const request=new XMLHttpRequest();job.xhr=request;
  request.open('GET',target.href,true);request.responseType='document';request.timeout=20000;
  request.setRequestHeader('Cache-Control','no-cache, no-store');
  request.onload=()=>{
   try{
    const resultUrl=new URL(request.responseURL||target.href);
    if(resultUrl.origin!==target.origin)throw Error('origin');
    const doc=request.responseXML;if(!doc){finish({error:'官网尚未返回可读取页面，请稍后重试。'});return;}
    finish({url:resultUrl.href,status:request.status,data:parse(doc,'updates',{page})});
   }catch(error){finish({error:'官网后台读取暂未完成，请点查看官网核对。'});}
  };
  request.onerror=()=>finish({error:'官网后台连接失败，请稍后重试或点查看官网。'});
  request.ontimeout=()=>finish({error:'官网后台读取超时，请稍后重试。'});
  request.onabort=()=>finish({error:'已停止本次官网读取。'});
  request.send();
 }catch(error){finish({error:'官网连接尚未就绪，请点查看官网连接一次。'});}
})
