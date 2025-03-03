package org.schabi.newpipe.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.BulletCommentsPlayerBinding;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class BulletCommentsView extends ConstraintLayout {
    private final String TAG = "BulletCommentsView";

    /**
     * Tuple of TextView and ObjectAnimator.
     */
    private static class AnimatedTextView {
        AnimatedTextView(final TextView textView, final ObjectAnimator animator) {
            this.textView = textView;
            this.animator = animator;
        }

        public final TextView textView;
        public final ObjectAnimator animator;
    }

    public BulletCommentsView(final Context context) {
        super(context);
        init(context);
    }

    public BulletCommentsView(final Context context,
                              final AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public BulletCommentsView(final Context context,
                              final AttributeSet attrs,
                              final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    //How to create custom view: https://maku77.github.io/android/ui/create-custom-view.html
    private void init(final Context context) {
        final View layout = LayoutInflater.from(context)
                .inflate(R.layout.bullet_comments_player, this);
        //Not this: BulletCommentsPlayerBinding.inflate(LayoutInflater.from(context));
        binding = BulletCommentsPlayerBinding.bind(this);
        //This does not work. post(this::setLayout);
    }

    /**
     * Whether setLayout() is called.
     */
    private boolean layoutSet = false;

    /**
     * Needs additional space to draw comments longer than the view size.
     */
    private void setLayout() {
        final int additionalWidth = additionalSpaceRelative * getWidth();
        binding.bottomRight.getLayoutParams().width = additionalWidth;
        requestLayout();
        Log.i(TAG, "Additional width: " + additionalWidth
                + ", container width: " + binding.bulletCommentsContainer.getWidth());
    }

    /**
     * Auto-generated binding class.
     * https://developer.android.com/topic/libraries/data-binding/generated-binding
     */
    private BulletCommentsPlayerBinding binding;
    /**
     * Additional width of this ViewGroup relative to the parent ViewGroup
     * to show comments longer than the view size.
     */
    private final int additionalSpaceRelative = 4;

    /**
     * Number of comment rows.
     */
    private final int commentsRowsCount = 11;
    private final double commentRelativeTextSize = 1 / 13.5;

    /**
     * Duration of comments.
     */
    private final float commentsDuration = 4;
    private final List<AnimatedTextView> animatedTextViews = new ArrayList<>();
    private final Random random = new Random();

    /**
     * Clear all child views.
     */
    public void clearComments() {
        animatedTextViews.clear();
        binding.bulletCommentsContainer.removeAllViews();
    }

    /**
     * An alias for pauseComments() or resumeComments().
     *
     * @param pause whether to pause.
     */
    public void setPauseComments(final boolean pause) {
        if (pause) {
            pauseComments();
        } else {
            resumeComments();
        }
    }

    /**
     * Pause animation of comments.
     */
    public void pauseComments() {
        animatedTextViews.stream().forEach(s -> s.animator.pause());
    }

    /**
     * Resume animation of comments.
     */
    public void resumeComments() {
        animatedTextViews.stream().forEach(s -> s.animator.resume());
    }

    /**
     * Draw comments by creating textViews.
     *
     * @param items comments.
     */
    public void drawComments(@NonNull final BulletCommentsInfoItem[] items) {
        if (!layoutSet) {
            setLayout();
            layoutSet = true;
        }
        //Log.v(TAG, "New comments count: " + items.length);
        final Context context = binding.bulletCommentsContainer.getContext();
        final int height = getHeight();
        final int width = getWidth();
        for (final BulletCommentsInfoItem item : items) {
            //Create TextView.
            final TextView textView = new TextView(context);
            textView.setTextColor(item.getArgbColor());
            textView.setText(item.getCommentText());
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    (float) (height * commentRelativeTextSize * item.getRelativeFontSize()));
            textView.setMaxLines(1);
            textView.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            final double commentSpace = 1 / 4.4 * height;
            if (item.getPosition() == BulletCommentsInfoItem.Position.REGULAR
            | true) {
                //Setting initial position by addView() won't work properly.
                //setTop(), ... etc. won't work.
                final int row = random.nextInt(commentsRowsCount);
                textView.setX(width);
                //To get width with getWidth(), it should be called inside post().
                //or it returns 0.
                textView.post(() -> {
                    //Create ObjectAnimator.
                    final int textWidth = textView.getWidth();
                    final int textHeight = textView.getHeight();
                    final ObjectAnimator animator = ObjectAnimator.ofFloat(
                            textView,
                            View.TRANSLATION_X,
                            width,
                            -textWidth
                    );

                    textView.setY((float) (height * (0.5 + row) / commentsRowsCount - textHeight / 2));
                    final AnimatedTextView animatedTextView = new AnimatedTextView(
                            textView, animator);
                    animatedTextViews.add(animatedTextView);
                    animator.setInterpolator(new LinearInterpolator());
                    animator.setDuration((long) (commentsDuration * 1000));
                    animator.addListener(new AnimatorListenerAdapter() {
                        public void onAnimationEnd(final Animator animation) {
                            binding.bulletCommentsContainer.removeView(textView);
                            animatedTextViews.remove(animatedTextView);
                        }
                    });
                    animator.start();
                });
            } else {
                // TODO: Non-regular comments not implemented.
                //textView.setY(random.nextInt(maxTextViewPosY));
            }
            binding.bulletCommentsContainer.addView(textView);
        }
        //Log.v(TAG, "Child count: " + binding.bulletCommentsContainer.getChildCount());
        //Log.v(TAG, "AnimatedTextView count: " + (long) animatedTextViews.size());
    }
}
