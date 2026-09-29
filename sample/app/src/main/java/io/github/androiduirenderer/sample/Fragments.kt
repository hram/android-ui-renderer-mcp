package io.github.androiduirenderer.sample

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class BookListFragment : Fragment(R.layout.fragment_book_list) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val books = SampleBooks.all
        view.findViewById<TextView>(R.id.header).text = getString(R.string.books_count, books.size)
        view.findViewById<View>(R.id.emptyState).visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        view.findViewById<RecyclerView>(R.id.bookList).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = BookAdapter(books) { (activity as MainActivity).openDetail(it.id) }
        }
    }
}

/** Master-detail: list on the left, the selected book in the included detail pane on the right. */
class LibraryFragment : Fragment(R.layout.fragment_library) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val books = SampleBooks.all
        val detail = view.findViewById<View>(R.id.detailPane)
        val placeholder = view.findViewById<View>(R.id.detailPlaceholder)
        view.findViewById<TextView>(R.id.libraryHeader).text = getString(R.string.books_count, books.size)
        lateinit var adapter: BookAdapter
        fun select(book: Book) {
            adapter.selectedId = book.id
            placeholder.visibility = View.GONE
            detail.visibility = View.VISIBLE
            detail.bindBook(book)
        }
        adapter = BookAdapter(books, ::select)
        view.findViewById<RecyclerView>(R.id.libraryList).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }
        books.firstOrNull()?.let(::select)
    }
}

class BookDetailFragment : Fragment(R.layout.fragment_book_detail) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.detailPane).bindBook(SampleBooks.byId(requireArguments().getLong(ARG_ID)))
    }

    companion object {
        private const val ARG_ID = "bookId"
        fun create(id: Long) = BookDetailFragment().apply { arguments = bundleOf(ARG_ID to id) }
    }
}

private fun View.bindBook(book: Book) {
    findViewById<ImageView>(R.id.detailCover).setImageResource(book.cover)
    findViewById<TextView>(R.id.detailTitle).text = book.title
    findViewById<TextView>(R.id.detailAuthor).text = book.author
    findViewById<TextView>(R.id.detailStatus).bindStatus(book.onLoan)
    findViewById<TextView>(R.id.detailDescription).text = book.description
}
