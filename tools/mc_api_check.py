#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Minecraft 侧符号事实核对（ground truth 表）

表中每一条都来自 mappings.dev 1.21 的逐字照抄结果，
或 Flashback 源码中的实际调用。禁止凭记忆修改。

对照方式：扫描本工程源码，凡出现表中的符号，核对其用法是否与事实一致。
"""
import os, re, sys

SRC = 'src/main/java'

# ------------------------------------------------------------------ 事实表
# 来源标注：
#   [MD]  = mappings.dev 1.21 逐字照抄
#   [FB]  = Flashback 1.21 分支源码实际调用
FACTS = {
    # ---- Minecraft ----
    'Minecraft.getInstance()':        ('public static', 'OK',  '[FB] 到处在用'),
    'Minecraft.getMainRenderTarget()': ('public RenderTarget', 'OK', '[MD] public RenderTarget h() getMainRenderTarget()'),
    'Minecraft.mainRenderTarget':     ('private final', 'FORBIDDEN', '[MD] private final RenderTarget an mainRenderTarget —— 外部不可访问，Flashback 靠 access widener'),
    'Minecraft.timer':                ('private final DeltaTracker$Timer', 'FORBIDDEN', '[MD] private final DeltaTracker$Timer R timer'),
    'Minecraft.ON_OSX':               ('public static final boolean', 'OK', '[MD] public static final boolean a ON_OSX；[FB] 169/1080 行在用'),
    # ---- GameRenderer ----
    'getProjectionMatrix':            ('public Matrix4f (double)', 'OK', '[MD] public org.joml.Matrix4f getProjectionMatrix(double)'),
    'getBasicProjectionMatrix':       ('同一方法的 Yarn 名', 'OK', '[MD] 同一行显示两个映射名'),
    'renderItemInHand':               ('private void (Camera,float,Matrix4f)', 'OK', '[MD] Mojmap 名；Yarn 叫 renderHand'),
    'renderHand':                     ('Yarn 名（Mojmap 下不存在）', 'FALLBACK', '注入用 require=0 兜底，匹配不到会静默跳过'),
    # ---- RenderTarget ----
    'RenderTarget.frameBufferId':     ('public int', 'OK', '[MD] 三查确认 public（首次误读为 protected，已交叉验证）'),
    'RenderTarget.colorTextureId':    ('protected int', 'FORBIDDEN', '[MD] protected —— 必须用 getColorTextureId()'),
    'RenderTarget.getColorTextureId()': ('public int', 'OK', '[MD] public int getColorTextureId()'),
    'RenderTarget.width':             ('public int', 'OK', '[MD] public int width'),
    'RenderTarget.height':            ('public int', 'OK', '[MD] public int height'),
    'RenderTarget.clear':             ('public void (boolean)', 'OK', '[MD] public void clear(boolean getError)'),
    'RenderTarget.bindWrite':         ('public void (boolean)', 'OK', '[MD] public void bindWrite(boolean)'),
    'RenderTarget.unbindWrite':       ('public void ()', 'OK', '[MD] public void unbindWrite()'),
    # ---- TextureTarget ----
    'new TextureTarget':              ('4 参 (int,int,boolean,boolean)', 'OK', '[FB] ExportJob 178 行：new TextureTarget(w, h, false, Minecraft.ON_OSX)'),
    # ---- DeltaTracker ----
    'DeltaTracker.Timer':             ('内部类', 'OK', '[MD] Minecraft.timer 类型为 DeltaTracker$Timer；[FB] ExportJob 314 行'),
    # ---- 渲染调用描述符 ----
    'render(DeltaTracker$Timer;Z)V':  ('两参 (Timer, boolean)', 'OK', '[FB] ExportJob 353 行：gameRenderer.render(timer, true)'),
    # ---- I18n ----
    'I18n.get':                       ('public static String (String)', 'OK', '[FB] 大量使用'),
}

# 需要人工确认、尚无确证的用法
UNVERIFIED = {
    'ImGui.separator()': 'Flashback 源码未直接观测到，但属标准 ImGui API，风险低',
    'GLSL 330 core':     'AnaglyphShader 用 #version 330 core，取决于 MC 的 GL 上下文',
    'glColorMask/glBlitFramebuffer': 'LWJGL 标准 API，MC 1.21.1 要求 GL 3.3+',
}

def scan():
    hits, forbid_hits = [], []
    files = []
    for root, _, fs in os.walk(SRC):
        for fn in fs:
            if fn.endswith('.java'):
                files.append(os.path.join(root, fn))
    for fp in files:
        t = open(fp, encoding='utf-8').read()
        lines = t.split('\n')
        for ln, raw in enumerate(lines, 1):
            s = raw.strip()
            if s.startswith(('//', '*', '/*')):
                continue
            for key, (sig, verdict, src) in FACTS.items():
                if 'import ' in s or s.startswith('@'):
                    continue
                # 匹配策略：
                #   "A.b" 形式 → 取最后一段（方法名/字段名）单独匹配，
                #   因为实际代码可能是 mc.getMainRenderTarget() 这种变量调用，
                #   只按全限定名匹配会漏报。
                if '.' in key:
                    short = key.split('.')[-1].rstrip('()')
                else:
                    short = key

                if verdict == 'FORBIDDEN':
                    # 禁用项必须要求"有接收者"：
                    #   形如 X.timer / X.mainRenderTarget / X.colorTextureId
                    # 否则会把局部变量 timer 误判成访问了 private 字段。
                    pat = r'(\.\s*' + re.escape(short) + r'\b)|(' + re.escape(key.replace('.', r'\.')) + r'\b)'
                    if re.search(pat, s):
                        forbid_hits.append((os.path.basename(fp), ln, key, sig, s[:70]))
                    continue

                if short not in s:
                    continue
                hits.append((os.path.basename(fp), ln, key, verdict))
    return hits, forbid_hits

def main():
    hits, forbid = scan()
    print('=' * 70)
    print('Minecraft 侧符号事实核对（ground truth = mappings.dev 1.21 + Flashback 源码）')
    print('=' * 70)
    print(f'命中已核实符号: {len(hits)} 处\n')

    print('【禁用项检查】—— 命中即编译失败：')
    if forbid:
        for fp, ln, key, sig, text in forbid:
            print(f'  ❌ {fp}:{ln}  使用了 {key}')
            print(f'     事实: {sig}')
            print(f'     代码: {text}')
    else:
        print('  ✅ 无 —— 未使用任何 private/protected 成员')

    print('\n【已核实符号使用情况】')
    agg = {}
    for fp, ln, key, verdict in hits:
        agg.setdefault((key, verdict), 0)
        agg[(key, verdict)] += 1
    for (key, verdict), c in sorted(agg.items()):
        mark = '✅' if verdict == 'OK' else '⚠️'
        print(f'  {mark} {key}  x{c}  ({verdict})')

    print('\n【尚无确证的用法】')
    for k, v in UNVERIFIED.items():
        print(f'  ⚠️ {k}\n     {v}')

    print('\n' + '=' * 70)
    print('判定:', '通过（无禁用项）' if not forbid else f'失败（{len(forbid)} 处禁用项）')
    return 1 if forbid else 0

if __name__ == '__main__':
    sys.exit(main())
