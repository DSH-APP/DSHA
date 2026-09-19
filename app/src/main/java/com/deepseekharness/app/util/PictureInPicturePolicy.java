package com.deepseekharness.app.util;

/** 系统画中画的启用条件与比例边界；不依赖 Android，便于回归低版本与异常尺寸。 */
public final class PictureInPicturePolicy {
    private PictureInPicturePolicy() { }

    public static boolean canEnter(boolean supported, boolean allowed, boolean contentReady,
                                   boolean leavingForActivity, boolean finishing) {
        return supported && allowed && contentReady && !leavingForActivity && !finishing;
    }

    /** Android 要求宽高比位于 1:2.39 至 2.39:1，超长屏使用留边而不裁切。 */
    public static int[] aspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) return new int[]{9, 16};
        if ((long) width * 239 < (long) height * 100) return new int[]{100, 239};
        if ((long) width * 100 > (long) height * 239) return new int[]{239, 100};
        return new int[]{width, height};
    }

    public static float scale(int width, int height, int viewportWidth, int viewportHeight) {
        if (width <= 0 || height <= 0 || viewportWidth <= 0 || viewportHeight <= 0) return 1f;
        return Math.min((float) width / viewportWidth, (float) height / viewportHeight);
    }

    public static String layout(String value) {
        return "portrait".equals(value) || "landscape".equals(value) ? value : "auto";
    }

    public static boolean sessionActive(long previewGeneration, long currentGeneration, boolean stopping, boolean userStopped) {
        return previewGeneration > 0 && previewGeneration == currentGeneration && !stopping && !userStopped;
    }

    /** 交换网页视口的宽高，让网页重新排版，不旋转文字或设备方向。 */
    public static int[] viewport(int width, int height, String layout) {
        if (width <= 0 || height <= 0) { width = 1080; height = 1920; }
        if ("landscape".equals(layout)) return new int[]{Math.max(width, height), Math.min(width, height)};
        if ("portrait".equals(layout)) return new int[]{Math.min(width, height), Math.max(width, height)};
        return new int[]{width, height};
    }

    /** 保留网页的逻辑宽度，按小窗实际比例重排高度，避免不同系统窗口尺寸留下大片空白。 */
    public static int[] fittedViewport(int width,int height,int windowWidth,int windowHeight,String layout) {
        int[] preferred=viewport(width,height,layout);
        if(windowWidth<=0||windowHeight<=0)return preferred;
        long fitted=Math.round((double)preferred[0]*windowHeight/windowWidth);
        return new int[]{preferred[0],(int)Math.max(1,Math.min(16384,fitted))};
    }
}
