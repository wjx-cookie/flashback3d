package com.flashback3d.stereo;

import org.joml.Vector3f;

/**
 * 虚拟屏幕几何 —— 描述"观众眼前的那块屏"长什么样、观众站在哪。
 *
 * <p>所有坐标定义在<b>相机空间</b>：相机位于原点、朝向 -Z。
 * 这样屏幕会跟着回放镜头走，等价于"观众站在大屏正前方，屏幕固定"。
 *
 * <p>把屏幕放在相机空间而不是世界空间的理由：
 * 裸眼 3D / 破框内容的物理屏是固定的，观众视角相对它小幅变化。
 * 若把屏放在世界空间，镜头一移动屏幕就"跑掉"，破框感立刻失效。
 */
public class ProjectionRig {

    /** 屏幕中心到眼点的距离（世界单位，米）。 */
    private float screenDistance = 12.0F;

    /** 屏幕宽度（世界单位）。 */
    private float screenWidth = 16.0F;

    /** 屏幕高度（世界单位）。 */
    private float screenHeight = 9.0F;

    /** 眼点相对屏幕中心的横向偏移。立体模式下由眼距自动覆盖。 */
    private float eyeOffsetX = 0.0F;

    /** 眼点相对屏幕中心的垂直偏移。正值表示观众站得比屏幕中心高。 */
    private float eyeOffsetY = 0.0F;

    /** 屏幕绕垂直轴的偏航角（弧度）。折角双屏下两块屏各取 ±half。 */
    private float yaw = 0.0F;

    /** 屏幕绕水平轴的俯仰角（弧度）。正值表示屏幕上缘后仰。 */
    private float pitch = 0.0F;

    /** 双目间距（眼距，世界单位）。人类平均约 0.064，Minecraft 场景建议放大。 */
    private float eyeSeparation = 0.6F;

    /** 折角双屏的夹角（度）。户外大屏常见 90°。 */
    private float cornerAngleDeg = 90.0F;

    public float screenDistance() { return screenDistance; }
    public float screenWidth() { return screenWidth; }
    public float screenHeight() { return screenHeight; }
    public float eyeOffsetX() { return eyeOffsetX; }
    public float eyeOffsetY() { return eyeOffsetY; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float eyeSeparation() { return eyeSeparation; }
    public float cornerAngleDeg() { return cornerAngleDeg; }

    public ProjectionRig setScreenDistance(float v) { this.screenDistance = v; return this; }
    public ProjectionRig setScreenWidth(float v) { this.screenWidth = v; return this; }
    public ProjectionRig setScreenHeight(float v) { this.screenHeight = v; return this; }
    public ProjectionRig setEyeOffsetX(float v) { this.eyeOffsetX = v; return this; }
    public ProjectionRig setEyeOffsetY(float v) { this.eyeOffsetY = v; return this; }
    public ProjectionRig setYaw(float v) { this.yaw = v; return this; }
    public ProjectionRig setPitch(float v) { this.pitch = v; return this; }
    public ProjectionRig setEyeSeparation(float v) { this.eyeSeparation = v; return this; }
    public ProjectionRig setCornerAngleDeg(float v) { this.cornerAngleDeg = v; return this; }

    /** 屏幕宽高比。 */
    public float aspect() {
        return screenHeight > 0.0F ? screenWidth / screenHeight : 16.0F / 9.0F;
    }

    /**
     * 计算某块屏幕的三个角点（相机空间）。
     *
     * @param planeIndex 折角双屏下取 0 或 1；单屏固定取 0
     * @param pa 输出：左下角
     * @param pb 输出：右下角
     * @param pc 输出：左上角
     */
    public void computeScreenCorners(int planeIndex, Vector3f pa, Vector3f pb, Vector3f pc) {
        float totalYaw = yaw;
        if (planeIndex == 1) {
            // 第二块屏绕垂直轴向另一侧偏转半个夹角
            totalYaw += (float) Math.toRadians(cornerAngleDeg) * 0.5F;
        } else if (planeIndex == 0 && cornerAngleDeg > 0.0F) {
            totalYaw -= (float) Math.toRadians(cornerAngleDeg) * 0.5F;
        }

        float cosY = (float) Math.cos(totalYaw);
        float sinY = (float) Math.sin(totalYaw);
        float cosP = (float) Math.cos(pitch);
        float sinP = (float) Math.sin(pitch);

        float hw = screenWidth * 0.5F;
        float hh = screenHeight * 0.5F;

        // 局部坐标 → 绕 Y 偏航 → 绕 X 俯仰 → 平移到屏幕距离处
        for (int corner = 0; corner < 3; corner++) {
            float lx = (corner == 1) ? hw : -hw;
            float ly = (corner == 2) ? hh : -hh;

            // yaw
            float x1 = lx * cosY;
            float z1 = -lx * sinY;
            // pitch
            float y2 = ly * cosP;
            float z2 = ly * sinP;

            Vector3f dst = (corner == 0) ? pa : (corner == 1) ? pb : pc;
            dst.set(x1, y2, -screenDistance + z1 + z2);
        }
    }

    /** 该模式下第 i 个视点的横向偏移（相对屏幕中心）。 */
    public float viewOffsetX(int viewIndex, int viewCount) {
        if (viewCount <= 1) {
            return eyeOffsetX;
        }
        // 多视点沿水平线等距分布，中心对齐
        float span = eyeSeparation * (viewCount - 1);
        return eyeOffsetX - span * 0.5F + eyeSeparation * viewIndex;
    }

    /** 该模式下第 i 个视点的垂直偏移。光场场合可据需要扩展为二维网格。 */
    public float viewOffsetY(int viewIndex, int viewCount) {
        return eyeOffsetY;
    }
}
