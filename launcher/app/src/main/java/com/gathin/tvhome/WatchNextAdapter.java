package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public final class WatchNextAdapter extends RecyclerView.Adapter<WatchNextAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(WatchNextItem item);
    }

    public interface OnKeyNavigationListener {
        boolean onKeyNavigation(View v, int keyCode, KeyEvent event, int position);
    }

    public interface OnCardFocusListener {
        void onCardFocus(View view, boolean hasFocus, int position);
    }

    private final Context context;
    private final List<WatchNextItem> items;
    private final OnItemClickListener listener;
    private final OnKeyNavigationListener navListener;
    private final OnCardFocusListener focusListener;
    private final Interpolator focusInterpolator = new DecelerateInterpolator(1.4f);

    public WatchNextAdapter(Context context, List<WatchNextItem> items,
                            OnItemClickListener listener, OnKeyNavigationListener navListener,
                            OnCardFocusListener focusListener) {
        this.context = context;
        this.items = items != null ? new java.util.ArrayList<>(items) : new java.util.ArrayList<>();
        this.listener = listener;
        this.navListener = navListener;
        this.focusListener = focusListener;
    }

    public void setItems(List<WatchNextItem> newItems) {
        this.items.clear();
        if (newItems != null) {
            this.items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_watch_next, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        WatchNextItem item = items.get(position);
        holder.title.setText(item.title);

        if (item.subtitle != null && !item.subtitle.trim().isEmpty()) {
            holder.subtitle.setText(item.subtitle);
            holder.subtitle.setVisibility(View.VISIBLE);
        } else {
            holder.subtitle.setVisibility(View.GONE);
        }

        // Minutes left and app name right beside it (e.g. "16 min left · ZEE5")
        String meta = "";
        if (item.duration != null && !item.duration.trim().isEmpty()) {
            meta = item.duration;
            if (item.appName != null && !item.appName.trim().isEmpty()) {
                meta += " · " + item.appName;
            }
        } else if (item.appName != null && !item.appName.trim().isEmpty()) {
            meta = item.appName;
        }

        if (!meta.isEmpty()) {
            holder.duration.setText(meta);
            holder.duration.setVisibility(View.VISIBLE);
        } else {
            holder.duration.setVisibility(View.GONE);
        }

        if (item.posterUri != null) {
            ImageLoader.load(holder.poster, item.posterUri, item.posterRes);
        } else if (item.posterRes != 0) {
            holder.poster.setImageResource(item.posterRes);
            holder.poster.setTag(R.id.poster, null);
        } else if (item.appIcon != null) {
            holder.poster.setImageDrawable(item.appIcon);
            holder.poster.setTag(R.id.poster, null);
        } else {
            holder.poster.setImageDrawable(null);
            holder.poster.setTag(R.id.poster, null);
        }

        float density = holder.itemView.getResources().getDisplayMetrics().density;
        // Keep pivots explicitly centered on the 200dp x 112dp card so scale animation is strictly symmetric
        holder.cardContainer.setPivotX(100f * density);
        holder.cardContainer.setPivotY(56f * density);
        holder.poster.setPivotX(100f * density);
        holder.poster.setPivotY(56f * density);

        // Set progress bar slider immediately without waiting for post layout pass
        if (item.progress > 0f) {
            holder.progressTrack.setVisibility(View.VISIBLE);
            int trackWidth = holder.progressTrack.getWidth() > 0 ? holder.progressTrack.getWidth() : (int) (176f * density);
            int fillWidth = Math.max((int) (4f * density), (int) (trackWidth * item.progress));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) holder.progressFill.getLayoutParams();
            if (lp.width != fillWidth) {
                lp.width = fillWidth;
                holder.progressFill.setLayoutParams(lp);
            }
            holder.progressTrack.post(() -> {
                if (item.progress > 0f && holder.progressTrack.getWidth() > 0) {
                    int w = Math.max((int) (4f * density), (int) (holder.progressTrack.getWidth() * item.progress));
                    FrameLayout.LayoutParams postLp = (FrameLayout.LayoutParams) holder.progressFill.getLayoutParams();
                    if (postLp.width != w) {
                        postLp.width = w;
                        holder.progressFill.setLayoutParams(postLp);
                    }
                }
            });
        } else {
            holder.progressTrack.setVisibility(View.GONE);
        }

        // Apple TV focus animation: card container scales up (1.04x), image inside zooms (1.10x parallax)
        holder.cardContainer.setOnFocusChangeListener((v, hasFocus) -> {
            holder.itemView.setTranslationZ(hasFocus ? 10f : 0f);
            holder.cardContainer.animate()
                    .scaleX(hasFocus ? 1.04f : 1f)
                    .scaleY(hasFocus ? 1.04f : 1f)
                    .translationZ(hasFocus ? 8f : 0f)
                    .setInterpolator(focusInterpolator)
                    .setDuration(180)
                    .start();

            holder.poster.animate()
                    .scaleX(hasFocus ? 1.10f : 1f)
                    .scaleY(hasFocus ? 1.10f : 1f)
                    .setInterpolator(focusInterpolator)
                    .setDuration(180)
                    .start();

            holder.playIcon.animate()
                    .alpha(hasFocus ? 1.0f : 0.85f)
                    .setInterpolator(focusInterpolator)
                    .setDuration(180)
                    .start();

            if (focusListener != null) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    focusListener.onCardFocus(holder.cardContainer, hasFocus, pos);
                }
            }
        });

        holder.cardContainer.setOnKeyListener((v, keyCode, event) -> {
            if (navListener != null) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    return navListener.onKeyNavigation(v, keyCode, event, pos);
                }
            }
            return false;
        });

        holder.cardContainer.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout cardContainer;
        final ImageView poster;
        final ImageView playIcon;
        final TextView title;
        final TextView subtitle;
        final TextView duration;
        final FrameLayout progressTrack;
        final View progressFill;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cardContainer = itemView.findViewById(R.id.cardContainer);
            poster = itemView.findViewById(R.id.poster);
            playIcon = itemView.findViewById(R.id.playIcon);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            duration = itemView.findViewById(R.id.duration);
            progressTrack = itemView.findViewById(R.id.progressTrack);
            progressFill = itemView.findViewById(R.id.progressFill);

            float density = itemView.getResources().getDisplayMetrics().density;
            final float cornerRadius = 18f * density;
            final android.view.ViewOutlineProvider outlineProvider = new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
                }
            };
            cardContainer.setOutlineProvider(outlineProvider);
            cardContainer.setClipToOutline(true);
            poster.setOutlineProvider(outlineProvider);
            poster.setClipToOutline(true);

            cardContainer.post(() -> {
                cardContainer.setPivotX(cardContainer.getWidth() / 2f);
                cardContainer.setPivotY(cardContainer.getHeight() / 2f);
            });
            poster.post(() -> {
                poster.setPivotX(poster.getWidth() / 2f);
                poster.setPivotY(poster.getHeight() / 2f);
            });
        }
    }
}
