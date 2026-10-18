#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Flashback3D 外部 API 调用一致性检查器（"模拟编译"的核心部分）

思路：
  没有 javac，但最容易出错的不是语法，而是"调用的方法到底存不存在、
  参数个数对不对"。这两件事可以用事实比对：
    1. 从 Flashback 真实源码提取 ImGui / ImGuiHelper / ReplayUI 的用法
    2. 解析本工程源码的实际调用，逐条比对

不检查（做不到）：类型推导、泛型、重载精确消解。
只检查：方法是否观测到、参数个数是否落在已观测范围内。
"""
import os, re, sys, collections

SRC = 'src/main/java'
FB_SRC = os.environ.get('FB_SRC', '/tmp/ig')

# ---------------------------------------------------------------- 1. 观测表
def scan_fb_calls():
    """从 Flashback 源码观测 ImGui.* / ImGuiHelper.* / ImDrawList.* 的调用。"""
    obs = collections.defaultdict(set)
    if not os.path.isdir(FB_SRC):
        return obs
    for fn in os.listdir(FB_SRC):
        if not fn.endswith('.java'):
            continue
        t = open(os.path.join(FB_SRC, fn), encoding='utf-8', errors='ignore').read()
        # 去掉注释行，避免把注释里的例子当成真实调用
        lines = [l for l in t.split('\n') if not l.strip().startswith(('//', '*', '/*'))]
        body = '\n'.join(lines)
        for m in re.finditer(r'\b(ImGui|ImGuiHelper|drawList|ImDrawList)\.(\w+)\s*\(', body):
            cls, meth = m.group(1), m.group(2)
            key = 'ImDrawList' if cls in ('drawList', 'ImDrawList') else cls
            # 数参数：从 ( 开始做括号配对
            i = m.end() - 1
            depth = 0
            args = 1
            j = i
            while j < len(body):
                c = body[j]
                if c == '(':
                    depth += 1
                elif c == ')':
                    depth -= 1
                    if depth == 0:
                        break
                elif c == ',' and depth == 1:
                    args += 1
                j += 1
            inner = body[i + 1:j].strip()
            if inner == '':
                args = 0
            obs[(key, meth)].add(args)
    return obs

# ---------------------------------------------------------------- 2. 本工程调用
def scan_own_calls():
    own = []
    for root, _, fs in os.walk(SRC):
        for fn in fs:
            if not fn.endswith('.java'):
                continue
            fp = os.path.join(root, fn)
            t = open(fp, encoding='utf-8', errors='ignore').read()
            lines = t.split('\n')
            for ln, raw in enumerate(lines, 1):
                s = raw.strip()
                if s.startswith(('//', '*', '/*')):
                    continue
                for m in re.finditer(r'\b(ImGui|ImGuiHelper|drawList|ImDrawList)\.(\w+)\s*\(', s):
                    cls, meth = m.group(1), m.group(2)
                    key = 'ImDrawList' if cls in ('drawList', 'ImDrawList') else cls
                    # 参数计数（可能在多行，取本行往后 3 行）
                    seg = '\n'.join(lines[ln - 1:ln + 2])
                    k = seg.find(m.group(0))
                    i = k + len(m.group(0)) - 1
                    depth = 0
                    args = 1
                    j = i
                    while j < len(seg):
                        c = seg[j]
                        if c == '(':
                            depth += 1
                        elif c == ')':
                            depth -= 1
                            if depth == 0:
                                break
                        elif c == ',' and depth == 1:
                            args += 1
                        j += 1
                    inner = seg[i + 1:j].strip()
                    if inner == '':
                        args = 0
                    own.append((os.path.basename(fp), ln, key, meth, args, s[:70]))
    return own

# ---------------------------------------------------------------- 3. 比对
def main():
    obs = scan_fb_calls()
    own = scan_own_calls()

    print('=' * 68)
    print('Flashback3D 外部 API 调用一致性检查（基于 Flashback 真实源码观测）')
    print('=' * 68)
    print(f'观测到的 Flashback 调用模式: {len(obs)} 个')
    print(f'本工程调用: {len(own)} 处\n')

    ok, unobserved, argc_mismatch = [], [], []
    for fp, ln, key, meth, args, text in own:
        k = (key, meth)
        if k not in obs:
            unobserved.append((fp, ln, key, meth, args, text))
        elif args not in obs[k]:
            argc_mismatch.append((fp, ln, key, meth, args, sorted(obs[k]), text))
        else:
            ok.append((fp, ln, key, meth, args))

    print(f'✅ 与观测一致: {len(ok)} 处')
    print(f'❌ 参数个数不匹配: {len(argc_mismatch)} 处')
    print(f'⚠️  Flashback 源码中未见（无法证实）: {len(unobserved)} 处\n')

    if argc_mismatch:
        print('-' * 68)
        print('参数个数不匹配（必错）：')
        for fp, ln, key, meth, args, expect, text in argc_mismatch:
            print(f'  {fp}:{ln}')
            print(f'    调用: {key}.{meth}({args} 参)')
            print(f'    观测: {key}.{meth}({expect} 参)')
            print(f'    代码: {text}')

    if unobserved:
        print('-' * 68)
        print('Flashback 源码中未见（需另找证据，风险待定）：')
        for fp, ln, key, meth, args, text in unobserved:
            print(f'  {fp}:{ln}  {key}.{meth}({args}参)')
            print(f'    {text}')

    print('-' * 68)
    # 按方法汇总
    print('\n本工程用到的调用汇总：')
    agg = collections.Counter((k, m, a) for _, _, k, m, a, _ in own)
    for (k, m, a), c in sorted(agg.items()):
        mark = '✅' if (k, m) in obs and a in obs[(k, m)] else ('❌' if (k, m) in obs else '⚠️')
        shown = sorted(obs[(k, m)]) if (k, m) in obs else '—'
        print(f'  {mark} {k}.{m}({a}参) x{c}   [Flashback 观测: {shown}]')

    return 1 if argc_mismatch else 0

if __name__ == '__main__':
    sys.exit(main())
