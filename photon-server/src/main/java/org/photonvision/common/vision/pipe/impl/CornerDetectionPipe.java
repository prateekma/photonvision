package org.photonvision.common.vision.pipe.impl;

import org.photonvision.common.vision.pipe.CVPipe;
import org.photonvision.common.vision.target.TrackedTarget;
import edu.wpi.first.wpilibj.geometry.Translation2d;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.apache.commons.math3.util.FastMath;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

public class CornerDetectionPipe
        extends CVPipe<
                        List<TrackedTarget>,
                        List<TrackedTarget>,
                        CornerDetectionPipe.CornerDetectionPipeParameters> {

    Comparator<Point> leftRightComparator = Comparator.comparingDouble(point -> point.x);
    Comparator<Point> verticalComparator = Comparator.comparingDouble(point -> point.y);
    MatOfPoint2f polyOutput = new MatOfPoint2f();

    @Override
    protected List<TrackedTarget> process(List<TrackedTarget> targetList) {
        for (var target : targetList) {
            // detect corners. Might implement more algorithms later but
            // APPROX_POLY_DP_AND_EXTREME_CORNERS should be year agnostic
            switch (params.cornerDetectionStrategy) {
                case APPROX_POLY_DP_AND_EXTREME_CORNERS:
                    {
                        var targetCorners =
                                detectExtremeCornersByApproxPolyDp(target, params.calculateConvexHulls);
                        target.setCorners(targetCorners);
                        break;
                    }
                default:
                    {
                        break;
                    }
            }
        }
        return targetList;
    }

    /**
    * @param target the target to find the corners of.
    * @return the corners. left top, left bottom, right bottom, right top
    */
    private List<Point> findBoundingBoxCorners(TrackedTarget target) {
        // extract the corners
        var points = new Point[4];
        target.m_mainContour.getMinAreaRect().points(points);

        // find the tl/tr/bl/br corners
        // first, min by left/right
        var list_ = Arrays.asList(points);
        list_.sort(leftRightComparator);
        // of this, we now have left and right
        // sort to get top and bottom
        var left = new ArrayList<>(List.of(list_.get(0), list_.get(1)));
        left.sort(verticalComparator);
        var right = new ArrayList<>(List.of(list_.get(2), list_.get(3)));
        right.sort(verticalComparator);

        // tl tr bl br
        var tl = left.get(0);
        var bl = left.get(1);
        var tr = right.get(0);
        var br = right.get(1);

        return List.of(tl, bl, br, tr);
    }

    /**
    * @param a First point.
    * @param b Second point.
    * @return The straight line distance between them.
    */
    private static double distanceBetween(Point a, Point b) {
        return FastMath.sqrt(FastMath.pow(a.x - b.x, 2) + FastMath.pow(a.y - b.y, 2));
    }

    /**
    * @param a First point.
    * @param b Second point.
    * @return The straight line distance between them.
    */
    private static double distanceBetween(Translation2d a, Translation2d b) {
        return FastMath.sqrt(
                FastMath.pow(a.getX() - b.getX(), 2) + FastMath.pow(a.getY() - b.getY(), 2));
    }

    /**
    * Find the 4 most extreme corners,
    *
    * @param target the target to track.
    * @param convexHull weather to use the convex hull of the target.
    * @return the 4 extreme corners of the contour.
    */
    private List<Point> detectExtremeCornersByApproxPolyDp(TrackedTarget target, boolean convexHull) {
        var centroid = target.getMinAreaRect().center;
        Comparator<Point> distanceProvider =
                Comparator.comparingDouble(
                        (Point point) ->
                                FastMath.sqrt(
                                        FastMath.pow(centroid.x - point.x, 2) + FastMath.pow(centroid.y - point.y, 2)));

        MatOfPoint2f targetContour;
        if (convexHull) {
            targetContour = target.m_mainContour.getConvexHull();
        } else {
            targetContour = target.m_mainContour.getMat2f();
        }

        /*
        approximating a shape around the contours
        Can be tuned to allow/disallow hulls
        we want a number between 0 and 0.16 out of a percentage from 0 to 100
        so take accuracy and divide by 600

        Furthermore, we know that the contour is open if we haven't done convex hulls
        and it has subcontours.
        */
        var isOpen = !convexHull && target.hasSubContours();
        var peri = Imgproc.arcLength(targetContour, true);
        Imgproc.approxPolyDP(
                targetContour, polyOutput, params.accuracyPercentage / 600.0 * peri, !isOpen);

        // we must have at least 4 corners for this strategy to work.
        // If we are looking for an exact side count that is handled here too.
        var pointList = new ArrayList<>(polyOutput.toList());
        if (pointList.size() < 4 || (params.exactSideCount && params.sideCount != pointList.size()))
            return null;

        target.setApproximateBoundingPolygon(polyOutput);

        // left top, left bottom, right bottom, right top
        var boundingBoxCorners = findBoundingBoxCorners(target);

        var distanceToTlComparator =
                Comparator.comparingDouble((Point p) -> distanceBetween(p, boundingBoxCorners.get(0)));

        var distanceToTrComparator =
                Comparator.comparingDouble((Point p) -> distanceBetween(p, boundingBoxCorners.get(3)));

        // top left and top right are the poly corners closest to the bouding box tl and tr
        pointList.sort(distanceToTlComparator);
        var tl = pointList.get(0);
        pointList.remove(tl);
        pointList.sort(distanceToTrComparator);
        var tr = pointList.get(0);
        pointList.remove(tr);

        // at this point we look for points on the left/right of the center of the remaining points
        // and maximize their distance from the center of the min area rectangle
        var leftList = new ArrayList<Point>();
        var rightList = new ArrayList<Point>();
        var averageXCoordinate = 0;
        for (var p : pointList) {
            averageXCoordinate += p.x;
        }
        averageXCoordinate /= pointList.size();

        // add points that are below the center of the min area rectangle of the target
        for (var p : pointList) {
            if (p.y
                    > target.m_mainContour.getBoundingRect().y
                            + target.m_mainContour.getBoundingRect().height / 2.0)
                if (p.x < averageXCoordinate) {
                    leftList.add(p);
                } else {
                    rightList.add(p);
                }
        }
        if (leftList.isEmpty() || rightList.isEmpty()) return null;
        leftList.sort(distanceProvider);
        rightList.sort(distanceProvider);
        var bl = leftList.get(leftList.size() - 1);
        var br = rightList.get(rightList.size() - 1);
        System.out.printf("Found points: TL (%s) BL (%s) BR (%s) TR (%s)\n", tl, bl, br, tr);
        return List.of(tl, bl, br, tr);
    }

    public static class CornerDetectionPipeParameters {
        private final DetectionStrategy cornerDetectionStrategy;

        private final boolean calculateConvexHulls;
        private final boolean exactSideCount;
        private final int sideCount;

        /** This number can be changed to change how "accurate" our approximate polygon must be. */
        private final double accuracyPercentage;

        public CornerDetectionPipeParameters(
                DetectionStrategy cornerDetectionStrategy,
                boolean calculateConvexHulls,
                boolean exactSideCount,
                int sideCount,
                double accuracyPercentage) {
            this.cornerDetectionStrategy = cornerDetectionStrategy;
            this.calculateConvexHulls = calculateConvexHulls;
            this.exactSideCount = exactSideCount;
            this.sideCount = sideCount;
            this.accuracyPercentage = accuracyPercentage;
        }
    }

    public enum DetectionStrategy {
        APPROX_POLY_DP_AND_EXTREME_CORNERS
    }
}