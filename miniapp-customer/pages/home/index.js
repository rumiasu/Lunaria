const page = require('../../utils/page');const api=require('../../utils/api');const f=require('../../utils/format')
page({data:{packages:[],services:[],balance:'0.00',name:''},
  async load(){const [catalog,me]=await Promise.all([api.request('/catalog'),api.request('/me')]);this.setData({packages:catalog.packages.map(f.decorate),services:catalog.services,name:me.customer.name,balance:f.money(me.customer.balance_cents)})},
  async purchase(e){const id=e.currentTarget.dataset.id;const offer=this.data.packages.find(x=>x.id===id);wx.showModal({title:'用余额购买套餐',content:`${offer.name}，实付 ¥${offer.priceText}，共 ${offer.total} 次。仅演示数据。默认按实付比例退卡；请先查看规则。`,success:res=>{if(res.confirm)this.run(async()=>{await api.mutate('/me/purchases',{packageId:id});await this.load();wx.showToast({title:'已发到卡包',icon:'success'})})}})},
  book(e){wx.navigateTo({url:`/pages/booking/index?service=${e.currentTarget.dataset.service}`})}
})
