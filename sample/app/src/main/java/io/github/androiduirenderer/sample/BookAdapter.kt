package io.github.androiduirenderer.sample

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BookAdapter(
    private val books: List<Book>,
    private val onClick: (Book) -> Unit,
) : RecyclerView.Adapter<BookAdapter.Holder>() {

    var selectedId: Long? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.cover)
        val title: TextView = view.findViewById(R.id.title)
        val author: TextView = view.findViewById(R.id.author)
        val meta: TextView = view.findViewById(R.id.meta)
        val status: TextView = view.findViewById(R.id.statusBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false))

    override fun getItemCount() = books.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val book = books[position]
        holder.cover.setImageResource(book.cover)
        holder.title.text = book.title
        holder.author.text = book.author
        holder.meta.text = "${book.year} · ${book.pages} pages"
        holder.status.bindStatus(book.onLoan)
        holder.itemView.isSelected = book.id == selectedId
        holder.itemView.setOnClickListener { onClick(book) }
    }
}

fun TextView.bindStatus(onLoan: Boolean) {
    setText(if (onLoan) R.string.status_on_loan else R.string.status_available)
    setBackgroundResource(if (onLoan) R.drawable.bg_badge_on_loan else R.drawable.bg_badge_available)
    setTextColor(context.getColor(if (onLoan) R.color.on_badge_on_loan else R.color.on_badge_available))
}
