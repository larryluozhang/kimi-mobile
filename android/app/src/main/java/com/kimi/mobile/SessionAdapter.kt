package com.kimi.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SessionAdapter(
    private val onClick: (SessionItem) -> Unit
) : RecyclerView.Adapter<SessionAdapter.VH>() {

    private val items = ArrayList<SessionItem>()

    fun submit(list: List<SessionItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvSessionTitle)
        val time: TextView = view.findViewById(R.id.tvSessionTime)
        val busy: TextView = view.findViewById(R.id.tvSessionBusy)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.title.text = s.title
        holder.time.text = formatTime(s.updatedAt)
        holder.busy.visibility = if (s.busy) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(s) }
    }

    private fun formatTime(iso: String): String {
        if (iso.length < 19) return iso
        return iso.substring(0, 19).replace('T', ' ')
    }
}
