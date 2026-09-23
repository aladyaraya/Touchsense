package com.insta360.kmpsdk.demo.touchscene;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * 图像处理工具：把 Python 脚本 edge_preview.py 与 MAX_outline.py 的核心逻辑
 * 移植到 Android OpenCV，生成二值 Bitmap 供触觉网格使用。
 *
 * <p>输出约定：黑色背景（静默） + 白色前景（振动）。</p>
 */
public final class OpenCvImageProcessor {

    static {
        System.loadLibrary("opencv_java4");
    }

    private OpenCvImageProcessor() { }

    /**
     * 自适应阈值边缘检测（对应 edge_preview.py 的 extract_adaptive_edges）。
     *
     * 流程：RGBA → 灰度 → 双边滤波 → CLAHE → adaptiveThreshold →
     *       闭运算 → 过滤小轮廓 → 绘制轮廓。
     */
    public static Bitmap processEdge(Bitmap source) {
        Mat rgba = bitmapToMat(source);
        Mat gray = new Mat();
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
        rgba.release();

        Mat filtered = new Mat();
        Imgproc.bilateralFilter(gray, filtered, 9, 75, 75);
        gray.release();

        Mat enhanced = new Mat();
        Imgproc.createCLAHE(2.0, new Size(8, 8)).apply(filtered, enhanced);
        filtered.release();

        int h = enhanced.rows();
        int w = enhanced.cols();
        int blockSize = Math.max(11, (int) (Math.max(h, w) * 0.02));
        if (blockSize % 2 == 0) blockSize++;

        Mat edges = new Mat();
        Imgproc.adaptiveThreshold(
                enhanced, edges, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                blockSize, 5);
        enhanced.release();

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        Mat closed = new Mat();
        Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel);
        edges.release();
        kernel.release();

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        closed.release();
        hierarchy.release();

        contours = filterContours(contours, h, w, 0.0005f, 10);

        Mat result = Mat.zeros(h, w, CvType.CV_8UC1);
        if (!contours.isEmpty()) {
            Imgproc.drawContours(result, contours, -1, new Scalar(255), 2);
        }

        for (MatOfPoint c : contours) {
            c.release();
        }

        Bitmap out = matToBitmap(result);
        result.release();
        return out;
    }

    /**
     * GrabCut 前景分割 + 外轮廓掩膜（对应 MAX_outline.py 的 process_image）。
     *
     * 流程：降采样 → GrabCut 中心矩形 → 放大回原尺寸 → 开闭运算 →
     *       HSV 阴影过滤 → 闭运算 → 提取最大连通域 → 返回填充掩膜。
     */
    public static Bitmap processOutline(Bitmap source) {
        Mat bgr = new Mat();
        Mat rgba = bitmapToMat(source);
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
        rgba.release();

        int h = bgr.rows();
        int w = bgr.cols();

        // 1. 降采样以加速 GrabCut
        int longEdge = Math.max(h, w);
        Mat smallBgr;
        double scale = 1.0;
        if (longEdge > 800) {
            scale = 800.0 / longEdge;
            smallBgr = new Mat();
            Imgproc.resize(bgr, smallBgr, new Size(Math.round(w * scale), Math.round(h * scale)));
        } else {
            smallBgr = bgr.clone();
        }
        int sh = smallBgr.rows();
        int sw = smallBgr.cols();

        // 2. GrabCut 初始化掩码：外边框背景，中心可能前景
        Mat mask = new Mat(sh, sw, CvType.CV_8UC1, new Scalar(Imgproc.GC_PR_BGD));
        int marginX = (int) Math.round(sw * 0.10);
        int marginY = (int) Math.round(sh * 0.12);
        Rect centerRect = new Rect(marginX, marginY, sw - 2 * marginX, sh - 2 * marginY);
        Mat centerMask = mask.submat(centerRect);
        centerMask.setTo(new Scalar(Imgproc.GC_PR_FGD));
        centerMask.release();

        Mat bgdModel = new Mat();
        Mat fgdModel = new Mat();
        Imgproc.grabCut(smallBgr, mask, new Rect(), bgdModel, fgdModel, 5, Imgproc.GC_INIT_WITH_MASK);
        smallBgr.release();
        bgdModel.release();
        fgdModel.release();

        // 3. 生成二值掩码并放大回原尺寸
        Mat fg = new Mat();
        Mat prFg = new Mat();
        Core.compare(mask, new Scalar(Imgproc.GC_FGD), fg, Core.CMP_EQ);
        Core.compare(mask, new Scalar(Imgproc.GC_PR_FGD), prFg, Core.CMP_EQ);
        Mat binary = new Mat();
        Core.bitwise_or(fg, prFg, binary);
        fg.release();
        prFg.release();
        mask.release();

        if (scale != 1.0) {
            Mat resized = new Mat();
            Imgproc.resize(binary, resized, new Size(w, h), 0, 0, Imgproc.INTER_NEAREST);
            binary.release();
            binary = resized;
        }

        // 4. 形态学处理
        Mat kernel15 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(15, 15));
        Mat opened = new Mat();
        Imgproc.morphologyEx(binary, opened, Imgproc.MORPH_OPEN, kernel15);
        binary.release();
        Mat closed = new Mat();
        Imgproc.morphologyEx(opened, closed, Imgproc.MORPH_CLOSE, kernel15);
        opened.release();

        // 5. HSV 阴影过滤：去掉暗且偏蓝的区域
        Mat hsv = new Mat();
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);
        bgr.release();
        List<Mat> channels = new ArrayList<>();
        Core.split(hsv, channels);
        hsv.release();
        Mat hue = channels.get(0);
        Mat val = channels.get(2);

        Mat valMask = new Mat();
        Core.compare(val, new Scalar(95), valMask, Core.CMP_LT);
        Mat hueLow = new Mat();
        Core.compare(hue, new Scalar(80), hueLow, Core.CMP_GT);
        Mat hueHigh = new Mat();
        Core.compare(hue, new Scalar(150), hueHigh, Core.CMP_LT);
        hue.release();
        val.release();

        Mat shadow = new Mat();
        Core.bitwise_and(valMask, hueLow, shadow);
        hueLow.release();
        valMask.release();
        Core.bitwise_and(shadow, hueHigh, shadow);
        hueHigh.release();

        Mat invertedShadow = new Mat();
        Core.bitwise_not(shadow, invertedShadow);
        shadow.release();
        Mat filtered = new Mat();
        Core.bitwise_and(closed, invertedShadow, filtered);
        closed.release();
        invertedShadow.release();

        // 6. 再次闭运算并提取最大连通域
        Mat finalClosed = new Mat();
        Imgproc.morphologyEx(filtered, finalClosed, Imgproc.MORPH_CLOSE, kernel15);
        filtered.release();
        kernel15.release();

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(finalClosed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        finalClosed.release();
        hierarchy.release();

        Mat result = Mat.zeros(h, w, CvType.CV_8UC1);
        if (!contours.isEmpty()) {
            MatOfPoint largest = contours.get(0);
            for (MatOfPoint c : contours) {
                if (Imgproc.contourArea(c) > Imgproc.contourArea(largest)) {
                    largest = c;
                }
            }
            List<MatOfPoint> drawList = new ArrayList<>();
            drawList.add(largest);
            Imgproc.drawContours(result, drawList, -1, new Scalar(255), Core.FILLED);
            for (MatOfPoint c : contours) {
                c.release();
            }
        }

        Bitmap out = matToBitmap(result);
        result.release();
        return out;
    }

    /**
     * 过滤过小轮廓并保留最大的 topN 个。
     */
    private static List<MatOfPoint> filterContours(List<MatOfPoint> contours, int h, int w, float minAreaRatio, int topN) {
        float minArea = h * w * minAreaRatio;
        List<MatOfPoint> filtered = new ArrayList<>();
        for (MatOfPoint c : contours) {
            if (Imgproc.contourArea(c) >= minArea) {
                filtered.add(c);
            }
        }
        filtered.sort((a, b) -> Double.compare(Imgproc.contourArea(b), Imgproc.contourArea(a)));
        if (filtered.size() > topN) {
            for (int i = topN; i < filtered.size(); i++) {
                filtered.get(i).release();
            }
            return new ArrayList<>(filtered.subList(0, topN));
        }
        return filtered;
    }

    private static Mat bitmapToMat(Bitmap source) {
        Mat mat = new Mat();
        Bitmap copy = source.copy(source.getConfig(), false);
        Utils.bitmapToMat(copy, mat);
        copy.recycle();
        return mat;
    }

    private static Bitmap matToBitmap(Mat source) {
        Mat rgba = new Mat();
        Imgproc.cvtColor(source, rgba, Imgproc.COLOR_GRAY2RGBA);
        Bitmap bitmap = Bitmap.createBitmap(source.cols(), source.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgba, bitmap);
        rgba.release();
        return bitmap;
    }
}
