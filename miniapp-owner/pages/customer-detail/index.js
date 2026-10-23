const page=require('../../utils/page');const api=require('../../utils/api');const f=require('../../utils/format')
page({data:{id:'',customer:null,cards:[],ledger:[],appointments:[],amount:'',reference:''},onLoad(q){this.setData({id:q.id||''})},
 async load(){const d=await api.request(`/owner/customers/${this.data.id}`);this.setData({customer:{...d.customer,balanceText:f.money(d.customer.balance_cents)},cards:d.cards.map(f.decorate),ledger:d.ledger.map(f.decorate),appointments:d.appointments.map(f.decorate)})},
 async recharge(){await this.run(async()=>{if(!this.data.reference.trim())throw new Error('请填写演示收款来源');await api.mutate('/owner/recharges',{customerId:this.data.id,amountCents:f.cents(this.data.amount),reference:this.data.reference});this.setData({amount:'',reference:''});await this.load();wx.showToast({title:'演示本金已登记',icon:'none'})})},
 reverse(e){wx.showModal({title:'冲正核销，保留原流水',editable:true,placeholderText:'填写纠错原因',success:r=>{if(r.confirm)this.run(async()=>{if(!r.content?.trim())throw new Error('纠错原因必填');await api.mutate(`/owner/ledger/${e.currentTarget.dataset.id}/reverse`,{reason:r.content});await this.load()})}})}
})
