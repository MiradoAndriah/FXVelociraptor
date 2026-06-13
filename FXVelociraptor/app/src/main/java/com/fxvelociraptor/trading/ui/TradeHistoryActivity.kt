package com.fxvelociraptor.trading.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fxvelociraptor.trading.model.Trade

class TradeHistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(RecyclerView(this).also {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = TradeAdapter(emptyList())
        })

        supportActionBar?.apply {
            title = "📈 Historique des trades"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressed(); return true }

    class TradeAdapter(private val trades: List<Trade>) : RecyclerView.Adapter<TradeAdapter.VH>() {
        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tv: TextView = itemView as TextView
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setPadding(32, 16, 32, 16)
                setTextColor(0xFFFFFFFF.toInt())
            }
            return VH(tv)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = trades[position]
            holder.tv.text = "${t.type} ${t.symbol} | ${String.format("%.2f", t.profit)}$"
        }
        override fun getItemCount() = trades.size
    }
}
