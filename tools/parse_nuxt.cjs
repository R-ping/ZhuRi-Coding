#!/usr/bin/env node
/**
 * 掘金文章页 window.__NUXT__ 解析器
 *
 * 掘金文章页把首屏数据以 `window.__NUXT__=(function(a,b,...){...}(...))` 形式内联在 HTML 中，
 * 其中包含完整正文 mark_content（markdown，含图片链接）、作者信息与标签。
 *
 * 用法:
 *   node parse_nuxt.js <html文件> [输出json文件]
 * 未指定输出文件时打印到 stdout。
 *
 * 退出码: 0 成功 / 2 参数缺失 / 3 未找到 __NUXT__ / 4 求值失败 / 5 序列化失败
 */
const fs = require('fs');
const vm = require('vm');

const inPath = process.argv[2];
const outPath = process.argv[3];
if (!inPath) {
  console.error('usage: node parse_nuxt.js <html> [out.json]');
  process.exit(2);
}

const html = fs.readFileSync(inPath, 'utf8');
const m = html.match(/window\.__NUXT__\s*=\s*([\s\S]*?)\s*<\/script>/);
if (!m) {
  console.error('NUXT_NOT_FOUND');
  process.exit(3);
}

const code = m[1].trim().replace(/;+$/, '');

let value;
try {
  const sandbox = {};
  vm.createContext(sandbox);
  value = vm.runInContext(code, sandbox, { timeout: 15000 });
} catch (e) {
  console.error('EVAL_FAIL: ' + e.message);
  process.exit(4);
}

let json;
try {
  json = JSON.stringify(value);
} catch (e) {
  console.error('STRINGIFY_FAIL: ' + e.message);
  process.exit(5);
}

if (outPath) {
  fs.writeFileSync(outPath, json);
  console.log('OK bytes=' + json.length);
} else {
  process.stdout.write(json);
}
