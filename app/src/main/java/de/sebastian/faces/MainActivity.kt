package de.sebastian.faces

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import de.sebastian.faces.ui.theme.FacesTheme

// DIAGNOSTIC STEP 2: add Theme
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FacesTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "Step 2 OK - Theme works",
                        modifier = Modifier.wrapContentSize(Alignment.Center)
                    )
                }
            }
        }
    }
}
