const money = value => (Number(value || 0) / 100).toFixed(2)
function cents(input) {
  const text = String(input).trim()
  if (!/^\d{1,7}(\.\d{1,2})?$/.test(text)) throw new Error('金额最多两位小数')
  const [whole, part = ''] = text.split('.'); const result = Number(whole) * 100 + Number(part.padEnd(2, '0'))
  if (result < 1 || result > 100000000) throw new Error('金额需在 0.01–1000000 元之间')
  return result
}
const labels = { PENDING:'待确认',CONFIRMED:'已确认',CANCELED:'已取消',DONE:'已完成',CORRECTED:'已纠错',ACTIVE:'使用中',REVIEW:'退卡处理中',RETURNED:'已退卡',REGISTERED:'已登记退回余额',REJECTED:'未批准',RESERVED_NO_PAYOUT:'本金已预留 · 未付款',RECHARGE:'本金充值',PURCHASE:'余额购卡',SERVICE:'服务履约',REVERSAL:'核销冲正',REFUND_TO_BALANCE:'退卡回原余额',BALANCE_REFUND_RESERVED:'储值退款本金预留',CONSUMER:'消费者原因',SHOP:'店铺原因',OTHER_NON_CONSUMER:'其他非消费者原因',DISPUTED:'原因有争议',PROPORTIONAL:'实付比例',ORIGINAL_PRICE:'原价扣已用',PRINCIPAL:'储值本金' }
const label = value => labels[value] || value
const date = value => String(value || '').replace('T',' ').replace(/\.\d+Z?$/,'').slice(0,16)
const today = () => new Date(Date.now() + 8*3600000).toISOString().slice(0,10)
const decorate = item => ({ ...item, statusText:label(item.status), amountText:money(item.amount_cents), paidText:money(item.paid_cents), priceText:money(item.tier_price_cents ?? item.price_cents), defaultPriceText:money(item.price_cents), timeText:date(item.starts_at), kindText:label(item.kind), reasonText:label(item.reason), remaining:Number(item.total || 0)-Number(item.used || 0) })
module.exports = { money,cents,label,date,today,decorate }
