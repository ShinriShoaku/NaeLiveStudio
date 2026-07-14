package ame.project.nlstudio.scene

import ame.project.nlstudio.R
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/**
 * Strip horizontal kecil di panel Editor yang nampilin tiap layer (gambar/video/teks) sebagai
 * thumbnail yang bisa DI-TAP buat milih layer itu (disinkronkan dgn tap-di-kanvas), dan tombol
 * hapus muncul begitu layer-nya lagi kepilih. Ini yang bikin "layer bisa diklik" gak cuma dari
 * kanvas, tapi juga dari daftar di bawahnya - lebih gampang kalau layer-nya ketutup layer lain.
 */
class EditorLayerAdapter(
    private val bitmapLoader: (SceneLayer) -> Bitmap?,
    private val onLayerClick: (SceneLayer) -> Unit,
    private val onLayerDelete: (SceneLayer) -> Unit
) : RecyclerView.Adapter<EditorLayerAdapter.LayerViewHolder>() {

    private val layers = mutableListOf<SceneLayer>()
    private var selectedId: String? = null

    fun submitList(newLayers: List<SceneLayer>, selectedLayerId: String?) {
        layers.clear()
        layers.addAll(newLayers.sortedBy { it.zIndex })
        selectedId = selectedLayerId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LayerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_editor_layer, parent, false)
        return LayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: LayerViewHolder, position: Int) {
        val layer = layers[position]
        holder.bind(layer, layer.id == selectedId, bitmapLoader, onLayerClick, onLayerDelete)
    }

    override fun getItemCount(): Int = layers.size

    class LayerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val root: View = itemView.findViewById(R.id.layerChipRoot)
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivLayerThumb)
        private val ivTypeBadge: ImageView = itemView.findViewById(R.id.ivLayerTypeBadge)
        private val ivDelete: ImageView = itemView.findViewById(R.id.ivLayerDelete)

        fun bind(
            layer: SceneLayer,
            isSelected: Boolean,
            bitmapLoader: (SceneLayer) -> Bitmap?,
            onClick: (SceneLayer) -> Unit,
            onDelete: (SceneLayer) -> Unit
        ) {
            val bmp = bitmapLoader(layer)
            if (bmp != null) ivThumb.setImageBitmap(bmp) else ivThumb.setImageResource(android.R.drawable.ic_menu_gallery)

            when (layer.type) {
                LayerType.VIDEO -> {
                    ivTypeBadge.setImageResource(android.R.drawable.ic_media_play)
                    ivTypeBadge.visibility = View.VISIBLE
                }
                LayerType.TEXT -> {
                    ivTypeBadge.setImageResource(android.R.drawable.ic_menu_edit)
                    ivTypeBadge.visibility = View.VISIBLE
                }
                else -> ivTypeBadge.visibility = View.GONE
            }

            root.setBackgroundResource(if (isSelected) R.drawable.bg_layer_border_selected else R.drawable.bg_layer_border)
            ivDelete.visibility = if (isSelected) View.VISIBLE else View.GONE
            ivDelete.setOnClickListener { onDelete(layer) }
            root.setOnClickListener { onClick(layer) }
        }
    }
}