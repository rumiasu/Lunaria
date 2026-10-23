const page=require('../../utils/page');const api=require('../../utils/api');const f=require('../../utils/format')
page({data:{id:'',card:null,ledger:[],refunds:[],reasons:['消费者原因','店铺原因','其他非消费者原因','原因有争议'],reasonIndex:0,evidence:'',quote:null},
 onLoad(query){this.setData({id:query.id||''})},
 async load(){const [card,me]=await Promise.all([api.request(`/me/cards/${this.data.id}`),api.request('/me')]);this.setData({card:f.decorate(card),ledger:me.ledger.filter(x=>x.card_id===card.id).map(f.decorate),refunds:me.refunds.filter(x=>x.card_id===card.id).map(f.decorate)})},
 reasonChange(e){this.setData({reasonIndex:Number(e.detail.value),quote:null})},
 refundBody(){return {cardId:this.data.id,reason:['CONSUMER','SHOP','OTHER_NON_CONSUMER','DISPUTED'][this.data.reasonIndex],algorithm:'PROPORTIONAL',evidence:this.data.evidence}},
 async trial(){await this.run(async()=>{const quote=await api.request('/me/refund-quote','POST',this.refundBody());this.setData({quote:{...quote,amountText:f.money(quote.amount_cents)}})})},
 async refund(){await this.run(async()=>{if(!this.data.quote)throw new Error('请先试算');await api.mutate('/me/refunds',this.refundBody());this.setData({quote:null});await this.load();wx.showToast({title:'已申请，待人工核对',icon:'none'})})}
})
