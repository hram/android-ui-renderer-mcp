package io.github.androiduirenderer.composesample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class CoverTone { BLUE, GREEN, AMBER }

data class ComposeBook(
    val id: Long,
    val title: String,
    val author: String,
    val year: Int,
    val pages: Int,
    val onLoan: Boolean,
    val tone: CoverTone,
    val description: String,
)

private val Brand = Color(0xFF2F5D8C)
private val Background = Color(0xFFF3F5F8)
private val TextPrimary = Color(0xFF1B2330)
private val TextSecondary = Color(0xFF5B6778)
private val DividerColor = Color(0xFFDDE2EA)

private val LightShelfColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    background = Background,
    onBackground = TextPrimary,
    surface = Color.White,
    onSurface = TextPrimary,
    outlineVariant = DividerColor,
)

private val DarkShelfColors = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF00315A),
    background = Color(0xFF101820),
    onBackground = Color(0xFFE3EAF2),
    surface = Color(0xFF17222D),
    onSurface = Color(0xFFE3EAF2),
    outlineVariant = Color(0xFF3C4957),
)

/** Project-level wrapper auto-detected by render_compose. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    ShelfTheme(dark = false, content = content)
}

@Composable
fun ShelfTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkShelfColors else LightShelfColors, content = content)
}

@Composable
fun ComposeBookItem(book: ComposeBook, selected: Boolean = false, language: String = "en") {
    val rowColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
    Surface(
        color = rowColor,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .clickable { },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(book, compact = true)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = book.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(book.author, color = TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${book.year} · ${book.pages} ${if (language == "ru") "стр." else "pages"}", color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            LoanBadge(book.onLoan, language)
        }
    }
}

@Composable
fun ComposeBookList(books: List<ComposeBook>, title: String = "4 books", selectedId: Long = -1L, language: String = "en") {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = TextSecondary,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(books, key = { it.id }) { book ->
                    ComposeBookItem(book = book, selected = book.id == selectedId, language = language)
                }
            }
        }
    }
}

@Composable
fun ComposeLibrary(books: List<ComposeBook>, selectedId: Long, language: String = "en") {
    val selected = books.firstOrNull { it.id == selectedId } ?: books.firstOrNull()
    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.requiredWidth(400.dp).fillMaxHeight()) {
            ComposeBookList(
                books = books,
                title = if (language == "ru") "${books.size} книги" else "${books.size} books",
                selectedId = selectedId,
                language = language,
            )
        }
        VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
        if (selected != null) {
            BookDetail(book = selected, language = language, modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
fun ComposeShelfScreen(
    books: List<ComposeBook>,
    selectedId: Long,
    loading: Boolean = false,
    language: String = "en",
    dark: Boolean = false,
    title: String = "Shelf",
) {
    ShelfTheme(dark = dark) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(if (language == "ru") "Поиск" else "Search", fontSize = 14.sp)
                        Spacer(Modifier.width(24.dp))
                        Text(if (language == "ru") "Сортировка" else "Sort", fontSize = 14.sp)
                    }
                }
                ComposeLibrary(books = books, selectedId = selectedId, language = language)
            }
            if (loading) LoadingOverlay(language = language)
        }
    }
}

@Composable
private fun BookDetail(book: ComposeBook, language: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(modifier = Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                BookCover(book, compact = false)
                Spacer(Modifier.width(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(book.title, fontWeight = FontWeight.Bold, fontSize = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.author, color = TextSecondary, fontSize = 17.sp)
                    Text("${book.year} · ${book.pages} ${if (language == "ru") "стр." else "pages"}", color = TextSecondary, fontSize = 14.sp)
                    LoanBadge(book.onLoan, language)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(book.description, fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { }) { Text(if (language == "ru") "Забронировать" else if (book.onLoan) "Reserve" else "Borrow") }
                Button(onClick = { }) { Text(if (language == "ru") "Подробнее" else "Details") }
            }
        }
    }
}

@Composable
private fun BookCover(book: ComposeBook, compact: Boolean) {
    val color = when (book.tone) {
        CoverTone.BLUE -> Color(0xFF5B8DEF)
        CoverTone.GREEN -> Color(0xFF4CAF7A)
        CoverTone.AMBER -> Color(0xFFE0A23B)
    }
    val width = if (compact) 44.dp else 120.dp
    val height = if (compact) 56.dp else 160.dp
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .semantics { contentDescription = "${book.title} cover" },
        contentAlignment = Alignment.Center,
    ) {
        Text(book.title.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (compact) 20.sp else 42.sp)
    }
}

@Composable
private fun LoanBadge(onLoan: Boolean, language: String) {
    val background = if (onLoan) Color(0xFFFCEBD7) else Color(0xFFDDF3E4)
    val foreground = if (onLoan) Color(0xFF8A4B08) else Color(0xFF1E6B3A)
    Surface(color = background, shape = RoundedCornerShape(20.dp)) {
        Text(
            text = if (onLoan) {
                if (language == "ru") "Выдана" else "On loan"
            } else {
                if (language == "ru") "В наличии" else "Available"
            },
            color = foreground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun LoadingOverlay(language: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x99101826)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(18.dp))
                Text(if (language == "ru") "Загрузка…" else "Loading…", fontWeight = FontWeight.Medium)
            }
        }
    }
}

fun demoBooks(): List<ComposeBook> = listOf(
    ComposeBook(1L, "The Pragmatic Programmer", "Andrew Hunt, David Thomas", 1999, 352, false, CoverTone.BLUE, "A practical guide to building software with care, curiosity and effective habits."),
    ComposeBook(2L, "Designing Data-Intensive Applications", "Martin Kleppmann", 2017, 616, true, CoverTone.GREEN, "The big ideas behind reliable, scalable and maintainable data systems."),
    ComposeBook(3L, "Refactoring", "Martin Fowler", 2018, 448, false, CoverTone.AMBER, "Improving existing code while preserving its behavior."),
    ComposeBook(4L, "Working Effectively with Legacy Code", "Michael Feathers", 2004, 456, false, CoverTone.BLUE, "Techniques for safely changing code that has little or no test coverage."),
)
