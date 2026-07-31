package org.enthusia.tags.daily;

final class DailyAnimationPlan {
    static final int BORDER_RING_LENGTH = 20;

    private DailyAnimationPlan() {
    }

    static Frame frame(int requestedFrame, int totalFrames) {
        if (totalFrames < 2) {
            throw new IllegalArgumentException("Daily animation requires at least two frames");
        }
        int frame = Math.max(0, Math.min(totalFrames - 1, requestedFrame));
        boolean finalFrame = frame == totalFrames - 1;
        int revealedDays = revealedDays(frame, totalFrames);
        int previousRevealedDays = frame == 0 ? 0 : revealedDays(frame - 1, totalFrames);
        int progressSegments = finalFrame ? DailyMenuModel.TRACK_LENGTH
            : Math.min(DailyMenuModel.TRACK_LENGTH,
                Math.max(1, 1 + frame * DailyMenuModel.TRACK_LENGTH / (totalFrames - 1)));
        int borderHead = frame * (BORDER_RING_LENGTH - 1) / (totalFrames - 1);
        return new Frame(frame, borderHead, revealedDays, progressSegments,
            frame >= 3, frame >= 5, revealedDays > previousRevealedDays,
            finalFrame, centerStage(frame, totalFrames));
    }

    private static int revealedDays(int frame, int totalFrames) {
        if (frame >= totalFrames - 1) {
            return DailyMenuModel.TRACK_LENGTH;
        }
        int revealWindow = Math.max(1, totalFrames - 5);
        return Math.min(DailyMenuModel.TRACK_LENGTH,
            Math.max(0, (frame - 2) * DailyMenuModel.TRACK_LENGTH / revealWindow));
    }

    private static CenterStage centerStage(int frame, int totalFrames) {
        if (frame < 3) {
            return CenterStage.LOADING;
        }
        if (frame < 6) {
            return CenterStage.ALIGNING;
        }
        if (frame < totalFrames - 5) {
            return CenterStage.DAY;
        }
        if (frame < totalFrames - 2) {
            return CenterStage.REWARD;
        }
        return CenterStage.READY;
    }

    record Frame(int number, int borderHead, int revealedDays, int progressSegments,
                 boolean showCurrentStreak, boolean showBestStreak, boolean revealAccent,
                 boolean finalFrame, CenterStage centerStage) {
    }

    enum CenterStage {
        LOADING,
        ALIGNING,
        DAY,
        REWARD,
        READY
    }
}
