package ame.project.nlstudio.scene

import ame.project.nlstudio.R
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Nampilin daftar scene tersimpan sebagai kartu horizontal dengan thumbnail hasil composite
 * (background + semua layer-nya), jadi user langsung lihat "oh scene ini begini" tanpa nebak.
 * Tap kartu = load ke editor + langsung ganti live. Tap ikon hapus = hapus scene (kecuali "Layar HP").
 */
class SceneAdapter(
    private val onSceneClick: (Scene) -> Unit,
    private val onSceneDelete: (Scene) -> Unit
) : RecyclerView.Adapter<SceneAdapter.SceneViewHolder>() {

    private val scenes = mutableListOf<Scene>()
    private var activeSceneId: String? = null

    fun submitList(newScenes: List<Scene>, activeId: String?) {
        scenes.clear()
        scenes.addAll(newScenes)
        activeSceneId = activeId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SceneViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_scene, parent, false)
        return SceneViewHolder(view)
    }

    override fun onBindViewHolder(holder: SceneViewHolder, position: Int) {
        val scene = scenes[position]
        holder.bind(scene, scene.id == activeSceneId, onSceneClick, onSceneDelete)
    }

    override fun getItemCount(): Int = scenes.size

    class SceneViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: View = itemView.findViewById(R.id.sceneCardRoot)
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivSceneThumb)
        private val tvName: TextView = itemView.findViewById(R.id.tvSceneName)
        private val tvActiveBadge: TextView = itemView.findViewById(R.id.tvSceneActiveBadge)
        private val ivDelete: ImageView = itemView.findViewById(R.id.ivSceneDelete)

        fun bind(scene: Scene, isActive: Boolean, onClick: (Scene) -> Unit, onDelete: (Scene) -> Unit) {
            tvName.text = scene.name

            when (scene.backgroundType) {
                BackgroundType.SCREEN -> ivThumb.setImageResource(android.R.drawable.ic_menu_camera)
                else -> {
                    val bmp = scene.thumbnailPath?.let { BitmapFactory.decodeFile(it) }
                    if (bmp != null) ivThumb.setImageBitmap(bmp)
                    else ivThumb.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            }

            cardRoot.setBackgroundResource(
                if (isActive) R.drawable.bg_scene_card_active else R.drawable.bg_scene_card
            )
            tvActiveBadge.visibility = if (isActive) View.VISIBLE else View.GONE

            // Scene "Layar HP" (screen record) selalu ada, gak boleh dihapus
            ivDelete.visibility = if (scene.backgroundType == BackgroundType.SCREEN) View.GONE else View.VISIBLE
            ivDelete.setOnClickListener { onDelete(scene) }

            itemView.setOnClickListener { onClick(scene) }
        }
    }
}