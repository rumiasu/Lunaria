const page=require('../../utils/page');const api=require('../../utils/api');const f=require('../../utils/format')
page({data:{balance:'0.00',fulfilled:'0.00',tier:'',ledger:[],refundAmount:'',evidence:'',refunds:[]},
 async load(){const me=await api.request('/me');this.setData({balance:f.money(me.customer.balance_cents),fulfilled:f.money(me.customer.fulfilled_cents),tier:me.customer.tier==='VIP'?'演示专属等级':'普通等级',ledger:me.ledger.map(f.decorate),refunds:me.refunds.filter(x=>x.target_type==='BALANCE').map(f.decorate)})},
 async requestRefund(){await this.run(async()=>{await api.mutate('/me/balance-refunds',{amountCents:f.cents(this.data.refundAmount),evidence:this.data.evidence});await this.load();wx.showToast({title:'已提交储值退款申请',icon:'none'})})}
})
