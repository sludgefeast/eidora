package de.sebastian.faces

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment

// DIAGNOSTIC STEP 1: absolute minimum. No permissions, no WorkManager,
// no Room, no ML, no custom theme, no navigation.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Step 1 OK",
                    modifier = Modifier.wrapContentSize(Alignment.Center)
                )
            }
        }
    }
}
