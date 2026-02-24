package com.example.vpu.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.vpu.R;

import java.util.List;

public class PhasePagerAdapter
        extends RecyclerView.Adapter<PhasePagerAdapter.ViewHolder> {

    public interface OnPhaseClickListener {
        void onPhaseClick(int position);
    }

    private final List<String> imageUrls;
    private final OnPhaseClickListener listener;

    public PhasePagerAdapter(
            List<String> imageUrls,
            OnPhaseClickListener listener
    ) {
        this.imageUrls = imageUrls;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_phase, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(
            @NonNull ViewHolder holder,
            int position
    ) {
        String imageUrl = imageUrls.get(position);

        Glide.with(holder.imageView.getContext())
                .load(imageUrl)
                .into(holder.imageView);

        holder.imageView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPhaseClick(position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return imageUrls.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        ImageView imageView;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.imagePhase);
        }
    }
}
