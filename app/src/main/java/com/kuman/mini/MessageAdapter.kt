package com.kuman.mini

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Message(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val bitmap: Bitmap? = null
)

class MessageAdapter(
    private val messages: List<Message>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Accent color for user bubbles; updated by MainActivity on theme change. */
    var accentColor: Int = 0
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        private const val VIEW_TYPE_IMAGE = 3
    }

    override fun getItemViewType(position: Int): Int {
        val m = messages[position]
        return when {
            m.bitmap != null -> VIEW_TYPE_IMAGE
            m.isUser -> VIEW_TYPE_USER
            else -> VIEW_TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> UserMessageViewHolder(
                layoutInflater.inflate(R.layout.item_message_user, parent, false)
            )
            VIEW_TYPE_IMAGE -> ImageMessageViewHolder(
                layoutInflater.inflate(R.layout.item_message_image, parent, false)
            )
            else -> AssistantMessageViewHolder(
                layoutInflater.inflate(R.layout.item_message_assistant, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is UserMessageViewHolder, is AssistantMessageViewHolder -> {
                val textView = holder.itemView.findViewById<TextView>(R.id.msg_content)
                textView.text = message.content
                if (accentColor != 0 && message.isUser) {
                    textView.backgroundTintList = ColorStateList.valueOf(accentColor)
                } else {
                    textView.backgroundTintList = null
                }
            }
            is ImageMessageViewHolder -> {
                holder.imageView.setImageBitmap(message.bitmap)
                holder.captionView.text = message.content
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class AssistantMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class ImageMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.msg_image)
        val captionView: TextView = view.findViewById(R.id.msg_caption)
    }
}
