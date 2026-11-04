(function(root){
  'use strict';
  const cancelled={cancelled:true};
  function scheduler(call,interval){
    const jobs=[];let running=false,lastStart=0;
    function drain(){
      if(running||!jobs.length)return;
      const job=jobs.shift();if(!job.valid()){job.resolve(cancelled);drain();return;}
      running=true;
      setTimeout(async()=>{
        try{if(!job.valid()){job.resolve(cancelled);return;}lastStart=Date.now();job.started();job.resolve(await call(job.operation,job.args));}
        catch(error){job.reject(error);}
        finally{running=false;drain();}
      },Math.max(0,lastStart+(interval==null?1000:interval)-Date.now()));
    }
    return (operation,args,valid,started)=>new Promise((resolve,reject)=>{jobs.push({operation,args,valid:valid||(()=>true),started:started||(()=>{}),resolve,reject});drain();});
  }
  function page(result,number,search){
    const books=result&&result.books,total=Number(result&&result.pages);
    if(!Array.isArray(books)||!Number.isInteger(total)||total<1||(!search&&!books.length&&number<=total)||books.some(b=>!b||!String(b.id||'').match(/^[1-9]\d*$/)||!String(b.title||'').trim()))throw new Error('本页未返回有效书目。');
    return {books,total};
  }
  function create(options){
    const state={sources:options.sources.map(s=>Object.assign({page:0,total:null,done:false,attempts:0,error:''},s)),books:[],seen:new Set(),rawPages:[],loading:false,priming:false,error:'',version:0,lastAdded:[],closed:false,nextIndex:0,target:null};
    let pending=null,primePending=null;
    const changed=()=>{if(options.changed)options.changed(state);};
    state.counts=()=>({loaded:state.sources.reduce((n,s)=>n+s.page,0),total:state.sources.every(s=>s.total!==null)?state.sources.reduce((n,s)=>n+s.total,0):null,done:state.sources.every(s=>s.done)});
    function append(record){
      const ranked=options.rank?options.rank(record.books,state,record.index):record.books,added=[];
      ranked.forEach(book=>{const id=String(book.id);if(!state.seen.has(id)){state.seen.add(id);state.books.push(book);added.push(book);}});return added;
    }
    function take(){
      if(state.closed)return Promise.resolve(state);
      if(pending)return pending;
      let index=-1;for(let n=0;n<state.sources.length;n++){const i=(state.nextIndex+n)%state.sources.length;if(!state.sources[i].done){index=i;break;}}
      if(index<0)return Promise.resolve(state);
      const source=state.sources[index],number=source.page+1;state.loading=true;state.target={name:source.name,page:number};state.error='';state.lastAdded=[];changed();
      const args={sort:source.sort,page:number};if(source.query)args.query=source.query;
      const task=options.request(source.operation||'feed',args,()=>!state.closed&&(!options.active||options.active()),()=>{source.attempts++;state.nextIndex=(index+1)%state.sources.length;});
      pending=(async()=>{
        try{
          const value=await task;if(value===cancelled||state.closed)return state;
          const result=page(value,number,source.operation==='search'||source.operation==='author');
          source.page=Math.min(number,result.total);source.total=result.total;source.done=number>=result.total;source.error='';
          const record={books:result.books,index:state.counts().loaded};state.rawPages.push(record);state.lastAdded=append(record);state.version++;
        }catch(error){if(!state.closed){source.error=error.message||'本页书目暂未加载成功';state.error=source.name+'：'+source.error;}}
        finally{state.loading=false;pending=null;changed();}
        return state;
      })();return pending;
    }
    state.next=()=>state.priming?primePending:take();
    state.prime=()=>{
      if(primePending)return primePending;
      state.priming=true;changed();
      primePending=(async()=>{try{while(!state.closed&&state.sources.some(s=>!s.attempts)&&(!options.active||options.active())){await take();if(state.sources.every(s=>!s.attempts))break;}}finally{state.priming=false;primePending=null;changed();}return state;})();return primePending;
    };
    state.rescore=()=>{state.books=[];state.seen.clear();state.rawPages.forEach(record=>append(record));state.lastAdded=[];state.version++;changed();};
    state.close=()=>{state.closed=true;};
    return state;
  }
  root.ReaderBookStream={scheduler,create,validatePage:page};
})(typeof window!=='undefined'?window:globalThis);
