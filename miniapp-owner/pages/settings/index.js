const page=require('../../utils/page');const api=require('../../utils/api');const f=require('../../utils/format')
page({data:{opens:'09:00',closes:'19:00',breakStart:'12:00',breakEnd:'13:00',capacity:'1',paused:false,threshold:'100.00',message:'',preserved:[]},
 async load(){const d=await api.request('/owner/dashboard');const s=d.shop;this.setData({opens:s.opens,closes:s.closes,breakStart:s.break_start,breakEnd:s.break_end,capacity:String(s.capacity),paused:s.paused,threshold:f.money(s.tier_threshold)})},
 timeChange(e){this.setData({[e.currentTarget.dataset.field]:e.detail.value})},toggle(e){this.setData({paused:e.detail.value})},
 async save(){await this.run(async()=>{const d=await api.mutate('/owner/settings',{opens:this.data.opens,closes:this.data.closes,breakStart:this.data.breakStart,breakEnd:this.data.breakEnd,capacity:Number(this.data.capacity),paused:this.data.paused,threshold:this.data.threshold==='0'?0:f.cents(this.data.threshold)});this.setData({message:d.message,preserved:d.preserved_appointments.map(f.decorate)});await this.load()})}
})
