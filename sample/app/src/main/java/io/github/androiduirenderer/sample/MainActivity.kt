package io.github.androiduirenderer.sample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val twoPane = resources.configuration.smallestScreenWidthDp >= 600
            supportFragmentManager.commit {
                replace(R.id.fragmentContainer, if (twoPane) LibraryFragment() else BookListFragment())
            }
        }
    }

    fun openDetail(id: Long) {
        supportFragmentManager.commit {
            replace(R.id.fragmentContainer, BookDetailFragment.create(id))
            addToBackStack(null)
        }
    }
}
