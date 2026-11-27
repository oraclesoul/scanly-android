package com.scanly.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.scanly.app.ui.CropScreen
import com.scanly.app.ui.DocumentScreen
import com.scanly.app.ui.HomeScreen
import com.scanly.app.ui.PageEditorScreen
import com.scanly.app.ui.ScannerScreen
import com.scanly.app.ui.ScanlyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScanlyTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                onScan = { nav.navigate("scan/new") },
                                onOpen = { nav.navigate("doc/$it") }
                            )
                        }
                        composable(
                            "scan/{docId}",
                            arguments = listOf(navArgument("docId") { type = NavType.StringType })
                        ) { entry ->
                            val arg = entry.arguments?.getString("docId")
                            val isNew = arg == null || arg == "new"
                            ScannerScreen(docId = if (isNew) null else arg) { id ->
                                if (isNew && id != null) {
                                    nav.navigate("doc/$id") {
                                        popUpTo("scan/{docId}") { inclusive = true }
                                    }
                                } else {
                                    nav.popBackStack()
                                }
                            }
                        }
                        composable(
                            "doc/{docId}",
                            arguments = listOf(navArgument("docId") { type = NavType.StringType })
                        ) { entry ->
                            val id = entry.arguments?.getString("docId").orEmpty()
                            DocumentScreen(
                                docId = id,
                                onBack = { nav.popBackStack() },
                                onScanMore = { nav.navigate("scan/$id") },
                                onOpenPage = { index -> nav.navigate("page/$id/$index") }
                            )
                        }
                        composable(
                            "page/{docId}/{index}",
                            arguments = listOf(
                                navArgument("docId") { type = NavType.StringType },
                                navArgument("index") { type = NavType.IntType }
                            )
                        ) { entry ->
                            val id = entry.arguments?.getString("docId").orEmpty()
                            val index = entry.arguments?.getInt("index") ?: 0
                            PageEditorScreen(
                                docId = id,
                                startIndex = index,
                                onBack = { nav.popBackStack() },
                                onCrop = { pageId -> nav.navigate("crop/$id/$pageId") }
                            )
                        }
                        composable(
                            "crop/{docId}/{pageId}",
                            arguments = listOf(
                                navArgument("docId") { type = NavType.StringType },
                                navArgument("pageId") { type = NavType.StringType }
                            )
                        ) { entry ->
                            CropScreen(
                                docId = entry.arguments?.getString("docId").orEmpty(),
                                pageId = entry.arguments?.getString("pageId").orEmpty(),
                                onBack = { nav.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
