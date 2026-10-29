'use strict';
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync(__dirname+'/web/app.js','utf8');
const controller=source.slice(source.indexOf('class ChatViewport'),source.indexOf('const viewport='));
const listeners={};let top=600,height=1000,anchorTop=-30;
const element={clientHeight:400,get scrollHeight(){return height;},get scrollTop(){return top;},set scrollTop(v){top=Math.max(0,Math.min(v,height-this.clientHeight));},addEventListener(name,fn){listeners[name]=fn;},getBoundingClientRect(){return {top:0};}};
const anchor={isConnected:true,getBoundingClientRect(){return {top:anchorTop,bottom:anchorTop+200};}};
const context={element,$:()=>({children:[anchor]})};vm.createContext(context);
vm.runInContext(controller+';this.view=new ChatViewport(element);',context);
const view=context.view;
// Following grows with output until the reader intentionally scrolls up.
let saved=view.capture();height+=50;view.restore(saved);assert.equal(top,650);
listeners.wheel({deltaY:-20});top-=20;listeners.scroll();assert.equal(view.pinned,false);
saved=view.capture();height+=100;view.restore(saved);assert.equal(top,630);
// Repeated output and completion cannot re-enable following within the old 90px zone.
for(let i=0;i<30;i++){saved=view.capture();height+=2;view.restore(saved);listeners.scroll();assert.equal(top,630);assert.equal(view.pinned,false);}
// Layout growth above the visible article preserves its visual position.
saved=view.capture();anchorTop+=80;height+=80;view.restore(saved);assert.equal(top,710);
// Returning to the actual bottom resumes following.
top=height-element.clientHeight;listeners.scroll();assert.equal(view.pinned,true);
saved=view.capture();height+=200;view.restore(saved);assert.equal(top,height-element.clientHeight);
listeners.touchstart({touches:[{clientY:100}]});listeners.touchmove({touches:[{clientY:120}]});assert.equal(view.pinned,false);
view.bottom();listeners.keydown({key:'PageUp',target:{closest:()=>null}});assert.equal(view.pinned,false);
view.bottom();assert.equal(view.pinned,true);
console.log('PASS: follow, upward intent, repeated output, anchor preservation, resume, touch and keyboard');
