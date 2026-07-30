package org.enthusia.tags.daily;

final class DailyAnimationPlan {
    private DailyAnimationPlan() {
    }

    static Frame frame(int requestedFrame, int totalFrames) {
        if (totalFrames < 2) {
            throw new IllegalArgumentException("Daily animation requires at least two frames");
        }
        int frame = Math.max(0, Math.min(totalFrames - 1, requestedFrame));
        int revealedDays = Math.min(DailyMenuModel.TRACK_LENGTH, Math.max(0, frame / 2));
        int previousRevealedDays = frame == 0
            ? 0 : Math.min(DailyMenuModel.TRACK_LENGTH, Math.max(0, (frame - 1) / 2));
        int progressSegments = Math.min(DailyMenuModel.TRACK_LENGTH,
            1 + (frame * DailyMenuModel.TRACK_LENGTH / (totalFrames - 1)));
        CenterStage centerStage = centerStage(frame, totalFrames);

        return new Frame(frame, frame % 24, revealedDays, progressSegments,
            frame >= 5, frame >= 7, revealedDays > previousRevealedDays,
            frame == totalFrames - 1, centerStage);
    }

    private static CenterStage centerStage(int frame, int totalFrames) {
        if (frame < 3) {
            return CenterStage.LOADING;
        }
        if (frame < 7) {
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
