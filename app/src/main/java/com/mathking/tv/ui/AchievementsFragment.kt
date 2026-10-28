package com.mathking.tv.ui

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.mathking.tv.App
import com.mathking.tv.R
import com.mathking.tv.data.StageCatalog
import com.mathking.tv.databinding.FragmentAchievementsBinding
import com.mathking.tv.stats.Achievements
import com.mathking.tv.stats.Analytics
import com.mathking.tv.util.Ui

class AchievementsFragment : Fragment(R.layout.fragment_achievements) {

    private var _b: FragmentAchievementsBinding? = null
    private val b get() = _b!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentAchievementsBinding.bind(view)
        val app = App.of(requireContext())
        app.repo.async({
            val stage = StageCatalog.byId(app.settings.stageId)
            val overall = app.repo.overall()
            val power = Analytics.power(
                overall.accuracy, overall.avgSeconds, stage.targetSeconds,
                overall.questions, stage.weight
            )
            val list = Achievements.load(app.repo, power)
            list to app.repo.overall()
        }) { pair ->
            if (!isAdded || _b == null) return@async
            render(pair.first, pair.second)
        }
    }

    private fun render(list: List<Achievements.Achievement>, overall: com.mathking.tv.data.Overall) {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        b.grid.removeAllViews()
        b.tvCount.text = "${list.count { it.unlocked }}/${list.size}"

        val gold = Ui.color(ctx, R.color.accent)
        val dim = Ui.color(ctx, R.color.text_dim)
        val green = Ui.color(ctx, R.color.green)

        list.forEachIndexed { i, a ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_card)
                isFocusable = true
                isClickable = true
                alpha = if (a.unlocked) 1f else 0.5f
                setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            }
            card.addView(TextView(ctx).apply {
                text = a.icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                gravity = Gravity.CENTER
            })
            card.addView(TextView(ctx).apply {
                text = a.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(if (a.unlocked) gold else dim)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            })
            card.addView(TextView(ctx).apply {
                text = a.desc
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(dim)
                gravity = Gravity.CENTER
                maxLines = 2
            })
            card.addView(TextView(ctx).apply {
                text = if (a.unlocked) "已达成" else a.progress
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (a.unlocked) green else dim)
                gravity = Gravity.CENTER
            })
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(i / 4, 1f),
                GridLayout.spec(i % 4, 1f)
            ).apply {
                width = 0
                height = 0
                setMargins(dp(5f), dp(5f), dp(5f), dp(5f))
            }
            b.grid.addView(card, lp)
        }
        b.grid.getChildAt(0)?.requestFocus()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
