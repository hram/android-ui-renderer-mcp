package io.github.androiduirenderer.sample

import androidx.annotation.DrawableRes

data class Book(
    val id: Long,
    val title: String,
    val author: String,
    val year: Int,
    val pages: Int,
    val onLoan: Boolean,
    @DrawableRes val cover: Int,
    val description: String,
)

/** Local stand-in for a data source. The renderer never runs this: render requests pass rows explicitly. */
object SampleBooks {
    val all = listOf(
        Book(1, "The Pragmatic Programmer", "David Thomas, Andrew Hunt", 1999, 352, false, R.drawable.cover_blue,
            "A practical guide to software craftsmanship: from small habits to how to think about a whole career."),
        Book(2, "Designing Data-Intensive Applications", "Martin Kleppmann", 2017, 616, true, R.drawable.cover_green,
            "How databases, streams and distributed systems actually behave, and which trade-offs they force on you."),
        Book(3, "Refactoring", "Martin Fowler", 2018, 448, false, R.drawable.cover_amber,
            "Improving the design of existing code in small, safe steps."),
        Book(4, "Working Effectively with Legacy Code", "Michael Feathers", 2004, 456, false, R.drawable.cover_blue,
            "Techniques for getting code without tests under control before changing it."),
    )

    fun byId(id: Long): Book = all.first { it.id == id }
}
