package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ui.FilmLabScreen
import com.example.ui.FilmLabViewModel
import com.example.ui.theme.DarkroomBlack
import com.example.ui.theme.FilmLabTheme

class MainActivity : ComponentActivity() {

    private val viewModel: FilmLabViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FilmLabTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkroomBlack
                ) {
                    FilmLabScreen(viewModel = viewModel)
                }
            }
        }
    }
}
